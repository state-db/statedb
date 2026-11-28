package app.statedb.app.lock;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.lock.StackLockedException;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackLockCancellerTest extends DatabaseTest {

  private static final String ORG_NAME = "canceller-org";

  @Inject StackLockCanceller canceller;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;
  private UUID lockId;

  @BeforeEach
  void seed() {
    TestDb.ActiveUpdate active =
        db.seedActiveUpdate(db.seedStack(ORG_NAME).stackId(), StackUpdate.Kind.PREVIEW);
    stackId = active.stackId();
    updateId = active.updateId();
    leaseId = active.leaseId();
    lockId = active.leaseId();
  }

  @Test
  void gracefulCancelRemovesLockAndTransitionsUpdate() {
    canceller.gracefulCancel(leaseId, stackId, updateId);

    assertCancelledAndUnlocked();
  }

  @Test
  void gracefulCancelWithWrongLeaseIdIsRejectedAndLeavesLockIntact() {
    UUID wrong = UUID.randomUUID();
    assertThrows(
        StackLockedException.class, () -> canceller.gracefulCancel(wrong, stackId, updateId));

    Number locks =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(1L, locks.longValue());

    String status =
        (String)
            em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    assertEquals("in-progress", status);

    UUID active =
        (UUID)
            em.createNativeQuery("SELECT active_update_id FROM stacks WHERE id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertNotNull(active);
  }

  @Test
  void gracefulCancelOnAlreadyTerminalUpdateRemovesLockButLeavesStatus() {
    // Simulate a status that some other code path has already moved to a terminal value. The
    // status flip is guarded on IN_PROGRESS, so cancel must still release the lock but leave
    // the terminal status alone.
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_updates SET status = 'failed', ended_at = now()"
                            + " WHERE id = ?1")
                    .setParameter(1, updateId)
                    .executeUpdate());

    canceller.gracefulCancel(leaseId, stackId, updateId);

    Number locks =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(0L, locks.longValue());

    String status =
        (String)
            em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    assertEquals("failed", status);
  }

  @Test
  void forceCancelUpdateDoesNotRequireLeaseId() {
    assertTrue(canceller.forceCancelUpdate(stackId, updateId));

    assertCancelledAndUnlocked();
  }

  @Test
  void forceCancelUpdateReturnsFalseForWrongStack() {
    assertFalse(canceller.forceCancelUpdate(UUID.randomUUID(), updateId));

    assertEquals(1L, StackLock.count("id", lockId));
    assertEquals("in-progress", updateStatus());
  }

  @Test
  void forceCancelLockUsesDisplayedLockId() {
    assertTrue(canceller.forceCancelLock(stackId, lockId));

    assertCancelledAndUnlocked();
  }

  @Test
  void forceCancelLockReturnsFalseForStaleLockId() {
    assertFalse(canceller.forceCancelLock(stackId, UUID.randomUUID()));

    assertEquals(1L, StackLock.count("id", lockId));
    assertEquals("in-progress", updateStatus());
  }

  private void assertCancelledAndUnlocked() {
    Number locks =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(0L, locks.longValue());

    assertEquals("cancelled", updateStatus());

    UUID active =
        (UUID)
            em.createNativeQuery("SELECT active_update_id FROM stacks WHERE id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertNull(active);
  }

  private String updateStatus() {
    return (String)
        em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
            .setParameter(1, updateId)
            .getSingleResult();
  }
}
