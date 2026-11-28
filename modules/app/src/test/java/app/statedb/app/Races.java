package app.statedb.app;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static java.util.concurrent.TimeUnit.SECONDS;

import jakarta.persistence.EntityManager;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Coordinates deterministic PostgreSQL lock-wait races where one transaction holds a lock while a
 * contender blocks on it, verified through {@code pg_stat_activity}.
 */
public final class Races {
  private Races() {}

  public static void holdThenContend(
      EntityManager em, Runnable acquireLock, String waitingQuery, Runnable contender)
      throws Exception {
    holdThenContend(
        em,
        acquireLock,
        waitingQuery,
        () -> {
          contender.run();
          return null;
        });
  }

  public static <T> T holdThenContend(
      EntityManager em, Runnable acquireLock, String waitingQuery, Callable<T> contender)
      throws Exception {
    var holderPid = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(3);
    try {
      Future<?> holder =
          pool.submit(
              () -> {
                try {
                  requiringNew()
                      .run(
                          () -> {
                            int pid =
                                ((Number)
                                        em.createNativeQuery("SELECT pg_backend_pid()")
                                            .getSingleResult())
                                    .intValue();
                            acquireLock.run();
                            holderPid.complete(pid);
                            try {
                              if (!release.await(15, SECONDS)) {
                                throw new AssertionError("Timed out waiting to release the holder");
                              }
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError("Interrupted while holding the lock", e);
                            }
                          });
                } catch (RuntimeException | Error e) {
                  holderPid.completeExceptionally(e);
                  throw e;
                }
              });
      int pid = holderPid.get(5, SECONDS);
      Future<T> contending = pool.submit(contender);
      pool.submit(
              () -> {
                awaitBlocked(em, pid, waitingQuery, contending);
                return null;
              })
          .get(6, SECONDS);

      release.countDown();
      holder.get(5, SECONDS);
      return contending.get(5, SECONDS);
    } finally {
      release.countDown();
      pool.shutdown();
      try {
        pool.awaitTermination(5, SECONDS);
      } finally {
        pool.shutdownNow();
      }
    }
  }

  private static void awaitBlocked(
      EntityManager em, int holderPid, String waitingQuery, Future<?> contender) throws Exception {
    long deadline = System.nanoTime() + SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (contender.isDone()) {
        contender.get();
        throw new AssertionError("Contender finished before waiting on the holder's lock");
      }
      // Check the query as well as its blocker because a later write can wait on the same row.
      boolean blocked =
          requiringNew()
              .call(
                  () ->
                      (Boolean)
                          em.createNativeQuery(
                                  """
                                  SELECT EXISTS (
                                    SELECT 1 FROM pg_stat_activity
                                    WHERE wait_event_type = 'Lock'
                                      AND ?1 = ANY(pg_blocking_pids(pid))
                                      AND query ILIKE ?2
                                  )
                                  """)
                              .setParameter(1, holderPid)
                              .setParameter(2, waitingQuery)
                              .getSingleResult());
      if (blocked) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Contender did not wait on the holder's lock within 5s");
  }
}
