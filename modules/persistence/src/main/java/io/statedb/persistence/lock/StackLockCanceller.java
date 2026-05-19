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
  public void cancel(UUID stackId, UUID updateId) {
    StackLock.delete("stackId", stackId);

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
