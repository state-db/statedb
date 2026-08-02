package app.statedb.persistence.lock;

import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.jboss.logging.Logger;

/**
 * Acquires the per-stack {@link StackLock}. The single {@code INSERT ... ON CONFLICT (stack_id) DO
 * UPDATE ... WHERE expires_at &lt; now()} statement is the atomic primitive: a live lock leaves the
 * {@code RETURNING} empty (caller maps to 409), an expired lock is stolen in place. Native SQL is
 * the justified escape hatch — {@code ON CONFLICT} is not expressible in HQL.
 *
 * <p>The same transaction also advances {@code stacks.active_update_id} and persists the
 * acquire-body fields onto the corresponding {@code stack_updates} row.
 *
 * <p>Ordered against compaction: a queued compaction finishes first, and this transaction takes the
 * {@code stack_state} row lock {@link app.statedb.persistence.stack.CompactionWorker} holds while
 * snapshotting, so compaction only ever sees complete updates.
 */
@ApplicationScoped
public class StackLockAcquirer {

  private static final Logger LOG = Logger.getLogger(StackLockAcquirer.class);
  private static final Duration COMPACTION_POLL = Duration.ofMillis(200);

  @Inject EntityManager em;

  @Inject LockConfig lockConfig;

  public Optional<Lease> acquire(
      UUID stackId,
      UUID updateId,
      String lockedBy,
      Integer journalVersion,
      Map<String, Object> tags) {
    awaitCompactionSettled(stackId);
    return QuarkusTransaction.requiringNew()
        .call(() -> acquireExclusive(stackId, updateId, lockedBy, journalVersion, tags));
  }

  /**
   * Waits out a queued compaction, giving {@code update -> compact -> next update}. Correctness
   * does not depend on it — the worker refuses to snapshot during an update — but without it
   * back-to-back updates could defer compaction indefinitely.
   */
  private void awaitCompactionSettled(UUID stackId) {
    Duration wait = lockConfig.compactionWait();
    // Zero disables it, for deployments and tests where no worker is running to clear the job.
    if (wait.isZero() || wait.isNegative()) {
      return;
    }
    Instant deadline = Instant.now().plus(wait);
    while (compactionQueued(stackId)) {
      if (!Instant.now().isBefore(deadline)) {
        LOG.warnf(
            "stack=%s taking lease with compaction still queued after %s; compaction will run"
                + " once this update completes",
            stackId, wait);
        return;
      }
      try {
        Thread.sleep(COMPACTION_POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private boolean compactionQueued(UUID stackId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                CompactionJob.count(
                        "stackId = ?1 and status in ?2",
                        stackId,
                        List.of(CompactionJob.Status.PENDING, CompactionJob.Status.RUNNING))
                    > 0);
  }

  private Optional<Lease> acquireExclusive(
      UUID stackId,
      UUID updateId,
      String lockedBy,
      Integer journalVersion,
      Map<String, Object> tags) {

    // Shared serialization point with compaction.
    StackState.find("stackId", stackId)
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .<StackState>firstResult();

    UUID lockId = UuidVersion7Strategy.INSTANCE.generateUuid(null);
    // 122 random bits from SecureRandom. The bearer is a short-lived secret
    // (≤ leaseTtl, hard-capped by maxLeaseLifetime) and is rotated on every
    // /renew_lease, so 122 bits is well past brute-force range for this
    // threat model — a 256-bit bearer would change the wire shape without
    // any practical security gain.
    UUID plaintextToken = UUID.randomUUID();
    byte[] tokenHash = LeaseTokenHasher.hash(plaintextToken);
    long ttlSeconds = lockConfig.leaseTtl().toSeconds();
    long maxLifetimeSeconds = lockConfig.maxLeaseLifetime().toSeconds();

    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                """
                INSERT INTO stack_locks (
                    id, stack_id, update_id, locked_by,
                    lock_token_hash, locked_at, expires_at, max_expires_at, heartbeat_at)
                VALUES (?1, ?2, ?3, ?4, ?5,
                        now(),
                        LEAST(
                            now() + (?6 || ' seconds')::interval,
                            now() + (?7 || ' seconds')::interval),
                        now() + (?7 || ' seconds')::interval,
                        now())
                ON CONFLICT (stack_id) DO UPDATE
                   SET id = EXCLUDED.id,
                       update_id = EXCLUDED.update_id,
                       locked_by = EXCLUDED.locked_by,
                       lock_token_hash = EXCLUDED.lock_token_hash,
                       locked_at = EXCLUDED.locked_at,
                       expires_at = EXCLUDED.expires_at,
                       max_expires_at = EXCLUDED.max_expires_at,
                       heartbeat_at = EXCLUDED.heartbeat_at
                 WHERE stack_locks.expires_at < now()
                RETURNING id, expires_at
                """)
            .setParameter(1, lockId)
            .setParameter(2, stackId)
            .setParameter(3, updateId)
            .setParameter(4, lockedBy)
            .setParameter(5, tokenHash)
            .setParameter(6, String.valueOf(ttlSeconds))
            .setParameter(7, String.valueOf(maxLifetimeSeconds))
            .getResultList();

    if (rows.isEmpty()) {
      // Transition the orphaned attempt to a terminal status so it doesn't pollute history /
      // status views forever. Guarded on status='in-progress' so a same-idempotency-key retry
      // after the row is already terminal is a no-op rather than tripping the terminal-status
      // trigger.
      StackUpdate.update(
          "status = ?1, endedAt = current_timestamp"
              + " where id = ?2 and stackId = ?3 and status = ?4",
          StackUpdate.Status.FAILED,
          updateId,
          stackId,
          StackUpdate.Status.IN_PROGRESS);
      return Optional.empty();
    }

    Object[] row = rows.get(0);
    UUID returnedLeaseId = (UUID) row[0];
    Instant expiresAt = toInstant(row[1]);

    Stack.update("activeUpdateId = ?1 where id = ?2", updateId, stackId);

    if (journalVersion != null || tags != null) {
      StackUpdate u = StackUpdate.findById(updateId);
      if (u != null && stackId.equals(u.stackId)) {
        if (journalVersion != null) {
          u.journalVersion = journalVersion;
        }
        if (tags != null) {
          u.tags = tags;
        }
      }
    }

    return Optional.of(
        new Lease(returnedLeaseId, plaintextToken, lockConfig.leaseTtl(), expiresAt));
  }

  // Hibernate's native-query timestamptz mapping varies across versions; accept either.
  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
