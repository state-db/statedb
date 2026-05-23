package io.statedb.persistence.lock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Extends a live lease and rotates its token. Rotation bounds the replay window of any leaked token
 * to a single lease window — once this commits, the previous plaintext is unrecoverable.
 *
 * <p>The {@code expires_at &gt; now()} predicate is the in-statement TOCTOU close: filter-time
 * validation can admit a lease that expired by the time this UPDATE runs.
 */
@ApplicationScoped
public class StackLockRenewer {

  @Inject EntityManager em;

  @Inject LockConfig lockConfig;

  @Transactional
  public Lease renew(UUID leaseId) {
    long ttlSeconds = lockConfig.leaseTtl().toSeconds();
    // 122 random bits from SecureRandom; see StackLockAcquirer for the
    // entropy rationale.
    UUID newPlaintext = UUID.randomUUID();
    byte[] newHash = LeaseTokenHasher.hash(newPlaintext);

    // expires_at is clamped to max_expires_at (set at acquire) so a bearer
    // that has been continuously renewed past the hard ceiling can't be kept
    // alive indefinitely. The predicate also rejects when the ceiling itself
    // has passed — even if the previous expires_at is still in the future,
    // renew refuses once we're past the cap.
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                """
                UPDATE stack_locks
                   SET expires_at = LEAST(
                           now() + (?2 || ' seconds')::interval,
                           max_expires_at),
                       heartbeat_at = now(),
                       lock_token_hash = ?3
                 WHERE id = ?1
                   AND expires_at > now()
                   AND max_expires_at > now()
                RETURNING id, expires_at
                """)
            .setParameter(1, leaseId)
            .setParameter(2, String.valueOf(ttlSeconds))
            .setParameter(3, newHash)
            .getResultList();

    if (rows.isEmpty()) {
      throw new LeaseExpiredException(leaseId);
    }
    Object[] row = rows.get(0);
    UUID returnedLeaseId = (UUID) row[0];
    Instant expiresAt = toInstant(row[1]);
    return new Lease(returnedLeaseId, newPlaintext, lockConfig.leaseTtl(), expiresAt);
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
