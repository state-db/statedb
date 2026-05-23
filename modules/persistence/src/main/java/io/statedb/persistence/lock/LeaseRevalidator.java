package io.statedb.persistence.lock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * In-transaction revalidation of a lease. Filter-time validation cannot prevent the lease from
 * expiring (or being stolen by a fresh acquire) before a mutating transaction actually writes.
 * Lease-scoped mutators call {@link #requireLive(UUID)} after taking their primary row lock; the
 * helper holds the lock row through commit so a concurrent cancel/complete cannot delete it mid
 * write.
 */
@ApplicationScoped
public class LeaseRevalidator {

  @Inject EntityManager em;

  /**
   * Throws {@link LeaseExpiredException} if the lock row is gone or has expired. The matched row is
   * locked {@code FOR UPDATE} so a concurrent {@code DELETE} (cancel) or {@code UPDATE} (complete,
   * renew) blocks until the caller commits. {@code clock_timestamp()} is used instead of {@code
   * now()} so the expiry check evaluates at the actual query time, not at transaction start — a
   * caller that started a transaction, blocked on its primary row, and then revalidated still sees
   * the current wall clock.
   */
  public void requireLive(UUID leaseId) {
    var rows =
        em.createNativeQuery(
                "SELECT 1 FROM stack_locks"
                    + " WHERE id = ?1 AND expires_at > clock_timestamp()"
                    + " FOR UPDATE")
            .setParameter(1, leaseId)
            .getResultList();
    if (rows.isEmpty()) {
      throw new LeaseExpiredException(leaseId);
    }
  }
}
