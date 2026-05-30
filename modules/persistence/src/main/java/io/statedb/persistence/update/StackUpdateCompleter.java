package io.statedb.persistence.update;

import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.lock.StackLockedException;
import io.statedb.persistence.stack.CompactionJobEnqueuer;
import io.statedb.persistence.stack.Stack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Terminates an in-progress {@link StackUpdate}: stamps {@code end_journal_seq}, flips the status,
 * deletes the lock row, and (for non-preview kinds) bumps {@code stacks.version}. All four writes
 * land in one JTA transaction so a forced failure rolls them back together.
 *
 * <p>Concurrency: takes a {@code FOR UPDATE} on {@code stack_state} for the stack first, which
 * serializes against {@link io.statedb.persistence.journal.JournalAppender} on the same row — so
 * {@code end_journal_seq} is captured after the last in-flight PATCH commits, never racing it.
 *
 * <p>The lease predicate is the surrogate {@code (id, stack_id, update_id)} tuple, matching {@link
 * io.statedb.persistence.lock.StackLockCanceller}. If the update has already moved to a terminal
 * status (e.g. concurrent cancel arrived between the CLI's last journal PATCH and {@code
 * /complete}), the lock is still released and the status flip / version bump are skipped — the
 * canceller already cleared {@code active_update_id} so the CLI's exit path stays clean.
 */
@ApplicationScoped
public class StackUpdateCompleter {

  @Inject EntityManager em;

  @Inject CompactionJobEnqueuer compactionJobEnqueuer;

  @Transactional
  public void complete(UUID leaseId, UUID stackId, UUID updateId, StackUpdate.Status terminal) {
    if (terminal != StackUpdate.Status.SUCCEEDED && terminal != StackUpdate.Status.FAILED) {
      throw new IllegalArgumentException("complete requires SUCCEEDED or FAILED, got " + terminal);
    }

    // Serialize against JournalAppender (which takes the same FOR UPDATE on stack_state) so
    // end_journal_seq is read after any in-flight PATCH on this stack has committed.
    long journalSeq =
        ((Number)
                em.createNativeQuery(
                        "SELECT journal_seq FROM stack_state WHERE stack_id = ?1 FOR UPDATE")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();

    long deleted =
        StackLock.delete("id = ?1 and stackId = ?2 and updateId = ?3", leaseId, stackId, updateId);
    if (deleted == 0) {
      throw new StackLockedException(stackId);
    }

    StackUpdate u = StackUpdate.findById(updateId);
    if (u == null || !stackId.equals(u.stackId)) {
      // The lease filter has already cross-checked path → lock row, so this is defense in depth.
      throw new StackLockedException(stackId);
    }

    if (u.status != StackUpdate.Status.IN_PROGRESS) {
      // A concurrent cancel beat us here. Lock is gone; leave status/version alone.
      return;
    }

    u.status = terminal;
    u.endedAt = Instant.now();
    u.endJournalSeq = journalSeq;

    if (u.kind == StackUpdate.Kind.PREVIEW) {
      Stack.update(
          "activeUpdateId = null where id = ?1 and activeUpdateId = ?2", stackId, updateId);
    } else {
      Stack.update(
          "version = version + 1, activeUpdateId = null" + " where id = ?1 and activeUpdateId = ?2",
          stackId,
          updateId);
    }

    compactionJobEnqueuer.enqueue(stackId);
  }
}
