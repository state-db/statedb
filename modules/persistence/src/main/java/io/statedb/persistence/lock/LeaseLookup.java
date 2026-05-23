package io.statedb.persistence.lock;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an {@code Authorization: update-token} value to a {@link LeaseContext}. The plaintext is
 * hashed once on entry and never stored; the DB query is a unique-index lookup on {@code
 * lock_token_hash}. Expired leases are filtered server-side so callers never see a stale row.
 *
 * <p>{@link #tokenMatches(UUID, UUID)} is provided for defense-in-depth checks (e.g. the optional
 * body token on {@code /renew_lease}); it does a hash + constant-time compare.
 */
@ApplicationScoped
public class LeaseLookup {

  public Optional<LeaseContext> findActive(UUID plaintextToken) {
    byte[] hash = LeaseTokenHasher.hash(plaintextToken);
    return StackLock.<StackLock>find("lockTokenHash = ?1 and expiresAt > current_timestamp", hash)
        .firstResultOptional()
        .map(l -> new LeaseContext(l.id, l.stackId, l.updateId));
  }

  /**
   * Returns true if {@code plaintextToken}'s hash equals the stored hash of the lease named by
   * {@code leaseId}. Comparison is constant-time on the hash bytes.
   */
  public boolean tokenMatches(UUID leaseId, UUID plaintextToken) {
    StackLock row = StackLock.findById(leaseId);
    if (row == null) {
      return false;
    }
    byte[] candidate = LeaseTokenHasher.hash(plaintextToken);
    return LeaseTokenHasher.constantTimeEquals(row.lockTokenHash, candidate);
  }
}
