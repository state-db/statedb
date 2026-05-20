package io.statedb.persistence.lock;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an {@code Authorization: update-token} value to a {@link LeaseContext}. Expired leases
 * are filtered out server-side so callers never see a stale row.
 */
@ApplicationScoped
public class LeaseLookup {

  public Optional<LeaseContext> findActive(UUID lockToken) {
    return StackLock.<StackLock>find("lockToken = ?1 and expiresAt > current_timestamp", lockToken)
        .firstResultOptional()
        .map(l -> new LeaseContext(l.stackId, l.updateId, l.lockToken));
  }
}
