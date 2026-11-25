package app.statedb.app.lock;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.lock.StackLockedException;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackLockCancellerTest {

  private static final String ORG_NAME = "canceller-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackLockCanceller canceller;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;
  private UUID lockId;

  @BeforeEach
  void seed() {
    stackId =
        read(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Canceller Test";
              org.persistAndFlush();
              StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
              return ref.stackId();
            });
    updateId =
        starter
            .start(
                stackId,
                StackUpdate.Kind.PREVIEW,
                null,
                null,
                "cancel-key",
                new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    leaseId = lease.leaseId();
    lockId = lease.leaseId();
  }

  @AfterEach
  void cleanup() {
    tx(
        () ->
            em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                .setParameter(1, ORG_NAME)
                .executeUpdate());
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
    tx(
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
