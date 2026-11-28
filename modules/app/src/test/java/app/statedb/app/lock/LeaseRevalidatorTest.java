package app.statedb.app.lock;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.app.Races;
import app.statedb.app.TestDb;
import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.persistence.lock.LeaseRevalidator;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LeaseRevalidatorTest extends DatabaseTest {

  private static final String ORG_NAME = "revalidator-org";

  @Inject StackLockCanceller canceller;
  @Inject LeaseRevalidator revalidator;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;

  @BeforeEach
  void seed() {
    TestDb.ActiveUpdate active =
        db.seedActiveUpdate(db.seedStack(ORG_NAME).stackId(), StackUpdate.Kind.PREVIEW);
    stackId = active.stackId();
    updateId = active.updateId();
    leaseId = active.leaseId();
  }

  @Test
  void liveLeaseDoesNotThrow() {
    assertDoesNotThrow(() -> revalidator.requireLive(leaseId));
  }

  @Test
  void expiredLeaseThrows() {
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks SET expires_at = now() - interval '1 second'"
                            + " WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(leaseId));
  }

  @Test
  void missingLeaseThrows() {
    requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM stack_locks WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());
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
                  requiringNew()
                      .run(
                          () -> {
                            revalidator.requireLive(leaseId);
                            locked.countDown();
                            await(release);
                          }));

      assertTrue(locked.await(5, TimeUnit.SECONDS));
      Future<?> second =
          pool.submit(() -> requiringNew().run(() -> revalidator.requireLive(leaseId)));

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
    Races.holdThenContend(
        em,
        () -> revalidator.requireLive(leaseId),
        "%stack_locks%",
        () -> canceller.gracefulCancel(leaseId, stackId, updateId));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
