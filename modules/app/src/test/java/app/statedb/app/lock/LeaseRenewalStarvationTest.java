package app.statedb.app.lock;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.LeaseRevalidator;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.lock.StackLockRenewer;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guards renew against starving behind a stream of share-locking appenders. */
@QuarkusTest
class LeaseRenewalStarvationTest {

  private static final String ORG_NAME = "starvation-org";
  // The CLI sends at most three batches at once, with a network round trip between them.
  private static final int APPENDER_THREADS = 3;
  private static final Duration APPENDER_GAP = Duration.ofMillis(10);
  private static final Duration TEST_DURATION = Duration.ofSeconds(3);
  private static final Duration APPENDER_HOLD = Duration.ofMillis(10);
  private static final Duration RENEW_INTERVAL = Duration.ofMillis(50);

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackLockRenewer renewer;
  @Inject LeaseRevalidator revalidator;
  @Inject EntityManager em;

  private UUID leaseId;

  @BeforeEach
  void seed() {
    UUID stackId =
        read(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Starvation Test";
              org.persistAndFlush();
              StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
              return ref.stackId();
            });
    UUID updateId =
        starter
            .start(
                stackId,
                StackUpdate.Kind.PREVIEW,
                null,
                null,
                "starve-key",
                new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    leaseId = lease.leaseId();
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
  void renewalLatencyStaysBoundedUnderConcurrentAppenders() throws Exception {
    AtomicBoolean stop = new AtomicBoolean(false);
    ExecutorService appenders = Executors.newFixedThreadPool(APPENDER_THREADS);
    for (int i = 0; i < APPENDER_THREADS; i++) {
      appenders.submit(
          () -> {
            while (!stop.get()) {
              tx(
                  () -> {
                    revalidator.requireLive(leaseId);
                    sleep(APPENDER_HOLD);
                  });
              sleep(APPENDER_GAP);
            }
          });
    }

    long[] latenciesMs = new long[200];
    long deadline = System.nanoTime() + TEST_DURATION.toNanos();
    int count = 0;
    while (System.nanoTime() < deadline && count < latenciesMs.length) {
      long start = System.nanoTime();
      renewer.renew(leaseId, null);
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      latenciesMs[count++] = elapsedMs;
      sleep(RENEW_INTERVAL);
    }

    stop.set(true);
    appenders.shutdown();
    assertTrue(appenders.awaitTermination(10, TimeUnit.SECONDS));

    long[] samples = Arrays.copyOf(latenciesMs, count);
    Arrays.sort(samples);
    long slowest = samples[samples.length - 1];

    // PostgreSQL lets share lockers pass a waiting writer, so one renewal can wait several holds.
    assertTrue(slowest < 5000, "slowest renewal took " + slowest + "ms");
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
