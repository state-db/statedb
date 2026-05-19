package io.statedb.persistence.lock;

import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/** Releases the lock, transitions the update to {@code cancelled}, and clears active_update_id. */
@ApplicationScoped
public class StackLockCanceller {

  @Transactional
  public void cancel(UUID stackId, UUID updateId, UUID lockToken) {
    // Predicate on the full lease tuple, not stack_id alone: between LeaseTokenFilter's check and
    // this transaction, a token can expire and a fresh acquire can take the stack with a new
    // token. Deleting by stack_id alone would release that new lease. Empty rowcount → 409.
    long deleted =
        StackLock.delete(
            "stackId = ?1 and updateId = ?2 and lockToken = ?3", stackId, updateId, lockToken);
    if (deleted == 0) {
      throw new StackLockedException(stackId);
    }

    StackUpdate u = StackUpdate.findById(updateId);
    if (u != null
        && stackId.equals(u.stackId)
        && u.status == StackUpdate.Status.IN_PROGRESS) {
      u.status = StackUpdate.Status.CANCELLED;
      u.endedAt = Instant.now();
    }

    Stack.update("activeUpdateId = null where id = ?1 and activeUpdateId = ?2", stackId, updateId);
  }
}
