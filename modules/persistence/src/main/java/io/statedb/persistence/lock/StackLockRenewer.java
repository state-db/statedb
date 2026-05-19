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
 * Extends a live lease by {@code lease-ttl} and bumps {@code heartbeat_at}. The {@code WHERE}
 * predicate names {@code (stack_id, update_id, lock_token, expires_at &gt; now())} so a token that
 * expired between {@link LeaseTokenFilter} validation and this UPDATE — possibly stolen by a fresh
 * acquire — cannot have its successor renewed. Empty rowcount → {@link StackLockedException}.
 */
@ApplicationScoped
public class StackLockRenewer {

  @Inject EntityManager em;

  @Inject LockConfig lockConfig;

  @Transactional
  public Lease renew(UUID stackId, UUID updateId, UUID lockToken) {
    long ttlSeconds = lockConfig.leaseTtl().toSeconds();

    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                """
                UPDATE stack_locks
                   SET expires_at = now() + (?4 || ' seconds')::interval,
                       heartbeat_at = now()
                 WHERE stack_id = ?1
                   AND update_id = ?2
                   AND lock_token = ?3
                   AND expires_at > now()
                RETURNING lock_token, expires_at
                """)
            .setParameter(1, stackId)
            .setParameter(2, updateId)
            .setParameter(3, lockToken)
            .setParameter(4, String.valueOf(ttlSeconds))
            .getResultList();

    if (rows.isEmpty()) {
      throw new StackLockedException(stackId);
    }
    Object[] row = rows.get(0);
    UUID returnedToken = (UUID) row[0];
    Instant expiresAt = toInstant(row[1]);
    return new Lease(returnedToken, lockConfig.leaseTtl(), expiresAt);
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
