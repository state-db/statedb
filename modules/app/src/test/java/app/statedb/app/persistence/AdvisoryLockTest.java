package app.statedb.app.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.AdvisoryLock;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AdvisoryLock#runExclusive} serializes concurrent callers cluster-wide via
 * the Postgres advisory lock — even when contention is purely in-process across multiple threads.
 * If serialization fails, callers see overlapping critical sections (counter mid-values visible to
 * each other) and total wall-clock drops to roughly one task's worth instead of N.
 */
@QuarkusTest
class AdvisoryLockTest {

  private static final long TEST_KEY = 0x4144564C4F434B01L; // ADVLOCK + test id 01

  @Inject AdvisoryLock advisoryLock;

  @Test
  void runExclusive_serializesConcurrentCallers() throws Exception {
    int callers = 4;
    Duration holdTime = Duration.ofMillis(150);
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxObservedOverlap = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Future<?>> futures = new ArrayList<>(callers);
    try {
      long t0 = System.nanoTime();
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  Arc.container().requestContext().activate();
                  try {
                    advisoryLock.runExclusive(
                        TEST_KEY,
                        () -> {
                          int now = inside.incrementAndGet();
                          maxObservedOverlap.accumulateAndGet(now, Math::max);
                          try {
                            Thread.sleep(holdTime.toMillis());
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          inside.decrementAndGet();
                          completed.incrementAndGet();
                        });
                  } finally {
                    Arc.container().requestContext().terminate();
                  }
                  return null;
                }));
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "callers did not finish within 30s");
      // Surface any worker exception that the in-process counters would otherwise hide
      // (e.g. one task throws before incrementing `completed`; the others still satisfy the
      // assertions).
      for (Future<?> f : futures) {
        f.get();
      }
      long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

      assertEquals(callers, completed.get(), "every caller must have run the task");
      assertEquals(
          1,
          maxObservedOverlap.get(),
          "advisory lock must keep at most one caller inside the critical section");
      long minExpectedMs = callers * holdTime.toMillis();
      assertTrue(
          elapsedMs >= minExpectedMs,
          "serialized callers should take >= "
              + minExpectedMs
              + "ms, actual was "
              + elapsedMs
              + "ms");
    } finally {
      pool.shutdownNow();
    }
  }
}
