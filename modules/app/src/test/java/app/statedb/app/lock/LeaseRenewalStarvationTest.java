package app.statedb.app.lock;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.lock.LeaseRevalidator;
import app.statedb.persistence.lock.StackLockRenewer;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Guards renew against starving behind a stream of share-locking appenders. */
@QuarkusTest
class LeaseRenewalStarvationTest extends DatabaseTest {

  private static final String ORG_NAME = "starvation-org";
  // The CLI sends at most three batches at once, with a network round trip between them.
  private static final int APPENDER_THREADS = 3;
  private static final Duration APPENDER_GAP = Duration.ofMillis(10);
  private static final Duration TEST_DURATION = Duration.ofSeconds(3);
  private static final Duration APPENDER_HOLD = Duration.ofMillis(10);
  private static final Duration RENEW_INTERVAL = Duration.ofMillis(50);

  @Inject StackLockRenewer renewer;
  @Inject LeaseRevalidator revalidator;

  private UUID leaseId;

  @BeforeEach
  void seed() {
    UUID stackId = db.seedStack(ORG_NAME).stackId();
    leaseId = db.seedActiveUpdate(stackId, StackUpdate.Kind.PREVIEW).leaseId();
  }

  @Test
  void renewalLatencyStaysBoundedUnderConcurrentAppenders() throws Exception {
    AtomicBoolean stop = new AtomicBoolean(false);
    ExecutorService appenders = Executors.newFixedThreadPool(APPENDER_THREADS);
    for (int i = 0; i < APPENDER_THREADS; i++) {
      appenders.submit(
          () -> {
            while (!stop.get()) {
              requiringNew()
                  .run(
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
