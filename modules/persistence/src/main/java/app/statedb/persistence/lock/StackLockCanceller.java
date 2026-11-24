package app.statedb.persistence.lock;

import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.vcs.ReportDeliveryWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Releases stack update locks, transitions live updates to {@code cancelled}, and clears
 * active_update_id.
 *
 * <p>The public methods preserve the caller's identity boundary: graceful cancellation must name
 * the authenticated lease row, while forced cancellation names the update or displayed lock
 * selected by an authorized non-holder. Each takes the {@code stack_state} row lock first, as every
 * other update writer does, so it never acts on a status another writer is changing.
 */
@ApplicationScoped
public class StackLockCanceller {

  @Inject ReportDeliveryWorker reportDeliveryWorker;

  @Transactional
  public void gracefulCancel(UUID leaseId, UUID stackId, UUID updateId) {
    lockStackState(stackId);
    long deleted =
        StackLock.delete("id = ?1 and stackId = ?2 and updateId = ?3", leaseId, stackId, updateId);
    if (deleted == 0) {
      throw new StackLockedException(stackId);
    }

    markCancelled(stackId, updateId);
  }

  @Transactional
  public boolean forceCancelUpdate(UUID stackId, UUID updateId) {
    lockStackState(stackId);
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
    lockStackState(stackId);
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

  private static void lockStackState(UUID stackId) {
    StackState.find("stackId", stackId)
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .<StackState>firstResult();
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
      reportDeliveryWorker.enqueue(u);
    }
    Stack.update("activeUpdateId = null where id = ?1 and activeUpdateId = ?2", stackId, updateId);
  }
}
