package io.statedb.persistence.lock;

import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Releases the lock, transitions the update to {@code cancelled}, and clears active_update_id.
 *
 * <p>Predicate is the surrogate {@code (id, stack_id, update_id)} tuple. {@link LeaseTokenFilter}
 * has already authenticated the caller by token-hash; once we hold the lease id we name the row
 * directly. The tuple guards against a caller smuggling a leaseId for a different stack/update —
 * defense in depth on top of the filter, which already cross-checks the path against the lock row.
 */
@ApplicationScoped
public class StackLockCanceller {

  @Transactional
  public void cancel(UUID leaseId, UUID stackId, UUID updateId) {
    long deleted =
        StackLock.delete("id = ?1 and stackId = ?2 and updateId = ?3", leaseId, stackId, updateId);
    if (deleted == 0) {
      throw new StackLockedException(stackId);
    }

    StackUpdate u = StackUpdate.findById(updateId);
    if (u != null && stackId.equals(u.stackId) && u.status == StackUpdate.Status.IN_PROGRESS) {
      u.status = StackUpdate.Status.CANCELLED;
      u.endedAt = Instant.now();
    }

    Stack.update("activeUpdateId = null where id = ?1 and activeUpdateId = ?2", stackId, updateId);
  }
}
