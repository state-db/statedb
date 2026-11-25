package app.statedb.persistence.update;

import app.statedb.persistence.lock.LockConfig;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Fails in-progress updates older than a lease TTL that hold no live lease, so a killed CLI does
 * not leave its stack in progress until some later update takes over.
 *
 * <p>Expired leases are already rejected everywhere, so no client outcome changes. Each reap
 * rechecks under the {@code stack_state} lock that acquisition and completion take.
 */
@ApplicationScoped
public class AbandonedUpdateReaper {

  private static final Logger LOG = Logger.getLogger(AbandonedUpdateReaper.class);
  private static final int PER_TICK_CAP = 100;

  @Inject EntityManager em;

  @Inject LockConfig lockConfig;

  @Inject StackUpdateCompleter completer;

  @Scheduled(every = "{statedb.lock.reap-interval}", concurrentExecution = ConcurrentExecution.SKIP)
  void scheduledTick() {
    reapOnce();
  }

  /** Fails up to the per-tick cap of abandoned updates. Returns how many were failed. */
  public int reapOnce() {
    Instant cutoff = databaseCutoff();
    int reaped = 0;
    for (Object[] row : candidates(cutoff)) {
      UUID updateId = (UUID) row[0];
      UUID stackId = (UUID) row[1];
      try {
        if (QuarkusTransaction.requiringNew().call(() -> reap(stackId, updateId, cutoff))) {
          reaped++;
          LOG.infof("stack=%s update=%s failed: abandoned without a live lease", stackId, updateId);
        }
      } catch (RuntimeException e) {
        // The update stays a candidate and the next tick retries it.
        LOG.warnf(e, "stack=%s update=%s abandoned-update reap failed", stackId, updateId);
      }
    }
    return reaped;
  }

  // Lease expiry is judged by the database clock, so the age cutoff must not depend on pod skew.
  private Instant databaseCutoff() {
    long epochMillis =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ((Number)
                            em.createNativeQuery(
                                    "SELECT (extract(epoch FROM now()) * 1000)::bigint")
                                .getSingleResult())
                        .longValue());
    return Instant.ofEpochMilli(epochMillis).minus(lockConfig.leaseTtl());
  }

  // Scalar projection over the in-progress partial index; hydrating entities buys nothing here.
  @SuppressWarnings("unchecked")
  private List<Object[]> candidates(Instant cutoff) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                em.createNativeQuery(
                        """
                        SELECT u.id, u.stack_id
                          FROM stack_updates u
                         WHERE u.status = 'in-progress'
                           AND u.started_at < ?1
                           AND NOT EXISTS (
                                 SELECT 1 FROM stack_locks l
                                  WHERE l.stack_id = u.stack_id
                                    AND l.update_id = u.id
                                    AND l.expires_at >= now())
                         ORDER BY u.started_at
                         LIMIT ?2
                        """)
                    .setParameter(1, cutoff)
                    .setParameter(2, PER_TICK_CAP)
                    .getResultList());
  }

  private boolean reap(UUID stackId, UUID updateId, Instant cutoff) {
    StackState state =
        StackState.find("stackId", stackId)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .<StackState>firstResult();
    StackUpdate u = StackUpdate.findById(updateId);
    if (state == null
        || u == null
        || !stackId.equals(u.stackId)
        || u.status != StackUpdate.Status.IN_PROGRESS
        || !u.startedAt.isBefore(cutoff)) {
      return false;
    }

    // Postgres rechecks the expiry after a concurrent renewal commits, so a renewed lease survives.
    StackLock.delete(
        "stackId = ?1 and updateId = ?2 and expiresAt < current_timestamp", stackId, updateId);
    if (StackLock.count("stackId = ?1 and updateId = ?2", stackId, updateId) > 0) {
      return false;
    }

    completer.failAbandoned(state, Stack.findById(stackId), u);
    return true;
  }
}
