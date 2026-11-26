package app.statedb.app.lock;

import static app.statedb.app.Races.holdThenContend;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.CommittedStack;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.persistence.lock.LeaseRevalidator;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LeaseRevalidatorTest {

  private static final String ORG_NAME = "revalidator-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackLockCanceller canceller;
  @Inject LeaseRevalidator revalidator;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;

  @BeforeEach
  void seed() {
    stackId = CommittedStack.create(stackCreator, ORG_NAME, "Revalidator Test");
    updateId =
        starter
            .start(
                stackId,
                StackUpdate.Kind.PREVIEW,
                null,
                null,
                "rev-key",
                new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    leaseId = lease.leaseId();
  }

  @AfterEach
  @Transactional
  void cleanup() {
    em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
        .setParameter(1, ORG_NAME)
        .executeUpdate();
  }

  @TestTransaction
  @Test
  void liveLeaseDoesNotThrow() {
    assertDoesNotThrow(() -> revalidator.requireLive(leaseId));
  }

  @TestTransaction
  @Test
  void expiredLeaseThrows() {
    em.createNativeQuery(
            "UPDATE stack_locks SET expires_at = now() - interval '1 second'" + " WHERE id = ?1")
        .setParameter(1, leaseId)
        .executeUpdate();
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(leaseId));
  }

  @TestTransaction
  @Test
  void missingLeaseThrows() {
    em.createNativeQuery("DELETE FROM stack_locks WHERE id = ?1")
        .setParameter(1, leaseId)
        .executeUpdate();
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(UUID.randomUUID()));
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(leaseId));
  }

  @Test
  void concurrentRequireLiveCallsDoNotBlockEachOther() throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  tx(
                      () -> {
                        revalidator.requireLive(leaseId);
                        locked.countDown();
                        await(release);
                      }));

      assertTrue(locked.await(5, TimeUnit.SECONDS));
      Future<?> second = pool.submit(() -> tx(() -> revalidator.requireLive(leaseId)));

      second.get(2, TimeUnit.SECONDS);

      release.countDown();
      holder.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentCancelStillWaitsForRequireLiveHolder() throws Exception {
    holdThenContend(
        em,
        () -> revalidator.requireLive(leaseId),
        "%delete%from %stack_locks%",
        () -> {
          canceller.gracefulCancel(leaseId, stackId, updateId);
          return null;
        });
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
