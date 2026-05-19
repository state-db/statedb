package io.statedb.persistence.lock;

import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Acquires the per-stack {@link StackLock}. The single {@code INSERT ... ON CONFLICT (stack_id) DO
 * UPDATE ... WHERE expires_at &lt; now()} statement is the atomic primitive: a live lock leaves the
 * {@code RETURNING} empty (caller maps to 409), an expired lock is stolen in place. Native SQL is
 * the justified escape hatch — {@code ON CONFLICT} is not expressible in HQL.
 *
 * <p>The same transaction also (a) advances {@code stacks.active_update_id} and (b) persists the
 * acquire-body fields onto the corresponding {@code stack_updates} row, since {@code
 * journal_version} and {@code tags} are sourced from this endpoint per db-schema.md.
 */
@ApplicationScoped
public class StackLockAcquirer {

  public static final Duration LEASE_TTL = Duration.ofHours(1);

  @Inject EntityManager em;

  @Transactional
  public Optional<Lease> acquire(
      UUID stackId,
      UUID updateId,
      String lockedBy,
      Integer journalVersion,
      Map<String, Object> tags) {

    UUID lockId = UUID.randomUUID();
    UUID lockToken = UUID.randomUUID();
    long ttlSeconds = LEASE_TTL.toSeconds();

    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                """
                INSERT INTO stack_locks (
                    id, stack_id, update_id, locked_by,
                    lock_token, locked_at, expires_at, heartbeat_at)
                VALUES (?1, ?2, ?3, ?4, ?5, now(), now() + (?6 || ' seconds')::interval, now())
                ON CONFLICT (stack_id) DO UPDATE
                   SET id = EXCLUDED.id,
                       update_id = EXCLUDED.update_id,
                       locked_by = EXCLUDED.locked_by,
                       lock_token = EXCLUDED.lock_token,
                       locked_at = EXCLUDED.locked_at,
                       expires_at = EXCLUDED.expires_at,
                       heartbeat_at = EXCLUDED.heartbeat_at
                 WHERE stack_locks.expires_at < now()
                RETURNING lock_token, expires_at
                """)
            .setParameter(1, lockId)
            .setParameter(2, stackId)
            .setParameter(3, updateId)
            .setParameter(4, lockedBy)
            .setParameter(5, lockToken)
            .setParameter(6, String.valueOf(ttlSeconds))
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
    UUID returnedToken = (UUID) row[0];
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

    return Optional.of(new Lease(returnedToken, LEASE_TTL, expiresAt));
  }

  // Hibernate's native-query timestamptz mapping varies across versions; accept either.
  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
