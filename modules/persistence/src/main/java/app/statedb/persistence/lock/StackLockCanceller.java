package app.statedb.persistence.lock;

import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Releases stack update locks, transitions live updates to {@code cancelled}, and clears
 * active_update_id.
 *
 * <p>The public methods preserve the caller's identity boundary: graceful cancellation must name
 * the authenticated lease row, while forced cancellation names the update or displayed lock
 * selected by an authorized non-holder.
 */
@ApplicationScoped
public class StackLockCanceller {

  @Transactional
  public void gracefulCancel(UUID leaseId, UUID stackId, UUID updateId) {
    long deleted =
        StackLock.delete("id = ?1 and stackId = ?2 and updateId = ?3", leaseId, stackId, updateId);
    if (deleted == 0) {
      throw new StackLockedException(stackId);
    }

    markCancelled(stackId, updateId);
  }

  @Transactional
  public boolean forceCancelUpdate(UUID stackId, UUID updateId) {
    StackUpdate u = StackUpdate.findById(updateId);
    if (u == null || !stackId.equals(u.stackId)) {
      return false;
    }

    StackLock.delete("stackId = ?1 and updateId = ?2", stackId, updateId);
    markCancelled(u, stackId, updateId);
    return true;
  }

  @Transactional
  public boolean forceCancelLock(UUID stackId, UUID lockId) {
    StackLock lock =
        StackLock.<StackLock>find("id = ?1 and stackId = ?2", lockId, stackId).firstResult();
    if (lock == null) {
      return false;
    }

    UUID updateId = lock.updateId;
    StackLock.delete("id = ?1 and stackId = ?2", lockId, stackId);
    markCancelled(stackId, updateId);
    return true;
  }

  private void markCancelled(UUID stackId, UUID updateId) {
    StackUpdate u = StackUpdate.findById(updateId);
    if (u == null || !stackId.equals(u.stackId)) {
      return;
    }
    markCancelled(u, stackId, updateId);
  }

  private void markCancelled(StackUpdate u, UUID stackId, UUID updateId) {
    if (u.status == StackUpdate.Status.IN_PROGRESS) {
      u.status = StackUpdate.Status.CANCELLED;
      u.endedAt = Instant.now();
    }
    Stack.update("activeUpdateId = null where id = ?1 and activeUpdateId = ?2", stackId, updateId);
  }
}
