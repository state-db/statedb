package app.statedb.app.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.journal.JournalAppender;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.update.AbandonedUpdateReaper;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

/** A CLI killed mid-run (e.g. a cancelled CI job) must not leave its stack in progress forever. */
@QuarkusTest
class AbandonedUpdateReaperTest {

  private static final String ORG_NAME = "reaper-org";
  // Past the 5m lease TTL, so the started-at grace no longer protects the update.
  private static final Duration LONG_AGO = Duration.ofHours(2);

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject AbandonedUpdateReaper reaper;
  @Inject StackLockCanceller canceller;
  @Inject JournalAppender journalAppender;
  @Inject EntityManager em;

  private UUID stackId;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Reaper Test";
                  org.persistAndFlush();
                  return stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of()).stackId();
                });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());
  }

  @Test
  void previewWhoseLeaseExpiredIsFailedAndReleasesTheStack() {
    UUID updateId = startPreview("k-expired");
    acquirer.acquire(stackId, updateId, "user:ci", 1, null).orElseThrow();
    backdate(updateId);
    expireLease();

    reaper.reapOnce();

    assertEquals(StackUpdate.Status.FAILED, status(updateId));
    assertNotNull(endedAt(updateId));
    long leases = QuarkusTransaction.requiringNew().call(() -> StackLock.count("stackId", stackId));
    assertEquals(0L, leases);
    assertNull(activeUpdateId());
  }

  @Test
  void updateThatNeverTookItsLeaseIsFailed() {
    UUID updateId = startPreview("k-never-started");
    backdate(updateId);

    reaper.reapOnce();

    assertEquals(StackUpdate.Status.FAILED, status(updateId));
  }

  @Test
  void recentUnstartedUpdateIsLeftAlone() {
    UUID updateId = startPreview("k-just-created");

    reaper.reapOnce();

    assertEquals(StackUpdate.Status.IN_PROGRESS, status(updateId));
  }

  @Test
  void longRunningUpdateWithLiveLeaseIsLeftAlone() {
    UUID updateId = startPreview("k-live");
    acquirer.acquire(stackId, updateId, "user:ci", 1, null).orElseThrow();
    backdate(updateId);

    reaper.reapOnce();

    assertEquals(StackUpdate.Status.IN_PROGRESS, status(updateId));
    assertEquals(updateId, activeUpdateId());
  }

  @Test
  void reapedUpdateCannotTakeALeaseAndTheStackStaysUsable() {
    UUID reapedId = startPreview("k-late-start");
    backdate(reapedId);
    reaper.reapOnce();

    assertTrue(acquirer.acquire(stackId, reapedId, "user:ci", 1, null).isEmpty());
    assertNull(activeUpdateId());

    UUID nextId = startPreview("k-next");
    assertTrue(acquirer.acquire(stackId, nextId, "user:ci", 1, null).isPresent());
    assertEquals(nextId, activeUpdateId());
  }

  @Test
  void killedApplyIsFinalizedLikeAFailedCompletion() throws Exception {
    UUID updateId = start(StackUpdate.Kind.UPDATE, "k-killed-up");
    Lease lease = acquirer.acquire(stackId, updateId, "user:ci", 1, null).orElseThrow();
    journalAppender.append(
        stackId,
        updateId,
        lease.leaseId(),
        List.of(
            new ObjectMapper()
                .readTree(
                    "{\"sequenceID\":1,\"kind\":0,\"operationID\":1,\"operation\":"
                        + "{\"resource\":{\"urn\":\"urn:a\",\"type\":\"t:a\"},"
                        + "\"type\":\"creating\"}}")));
    backdate(updateId);
    expireLease();

    reaper.reapOnce();

    long journalSeq =
        ((Number)
                em.createNativeQuery("SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(StackUpdate.Status.FAILED, status(updateId));
    // A null boundary would make `pulumi stack export --version 1` a 404.
    assertEquals(
        journalSeq,
        QuarkusTransaction.requiringNew()
            .call(() -> StackUpdate.<StackUpdate>findById(updateId).endJournalSeq));
    assertEquals(
        1, QuarkusTransaction.requiringNew().call(() -> Stack.<Stack>findById(stackId).version));
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> CompactionJob.count("stackId", stackId)) > 0);
  }

  @Test
  void leaseRenewedWhileTheReaperWaitsSurvives() throws Exception {
    UUID updateId = startPreview("k-renew-race");
    acquirer.acquire(stackId, updateId, "user:ci", 1, null).orElseThrow();
    backdate(updateId);
    expireLease();

    CountDownLatch renewed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // Stands in for StackLockRenewer: the row is renewed but not yet committed.
      Future<?> renewal =
          pool.submit(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(
                          () -> {
                            StackLock.update(
                                "expiresAt = ?1 where updateId = ?2",
                                Instant.now().plus(Duration.ofMinutes(5)),
                                updateId);
                            renewed.countDown();
                            await(release);
                          }));
      renewed.await(10, TimeUnit.SECONDS);
      Future<Integer> reap = pool.submit(() -> reaper.reapOnce());
      awaitLockWaiter();
      release.countDown();
      renewal.get(10, TimeUnit.SECONDS);
      reap.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertEquals(StackUpdate.Status.IN_PROGRESS, status(updateId));
    assertEquals(
        1L, QuarkusTransaction.requiringNew().call(() -> StackLock.count("updateId", updateId)));
    assertEquals(updateId, activeUpdateId());
  }

  @Test
  void forcedCancelWaitingOnAReapLeavesTheUpdateFailed() throws Exception {
    UUID updateId = startPreview("k-cancel-race");
    acquirer.acquire(stackId, updateId, "user:ci", 1, null).orElseThrow();

    CountDownLatch reaping = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // Stands in for a reap that has failed the update but not yet committed.
      Future<?> reap =
          pool.submit(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(
                          () -> {
                            em.createNativeQuery(
                                    "SELECT 1 FROM stack_state WHERE stack_id = ?1 FOR UPDATE")
                                .setParameter(1, stackId)
                                .getResultList();
                            StackLock.delete("updateId", updateId);
                            StackUpdate.update(
                                "status = ?1, endedAt = ?2 where id = ?3",
                                StackUpdate.Status.FAILED,
                                Instant.now(),
                                updateId);
                            reaping.countDown();
                            await(release);
                          }));
      reaping.await(10, TimeUnit.SECONDS);
      Future<Boolean> cancel = pool.submit(() -> canceller.forceCancelUpdate(stackId, updateId));
      awaitLockWaiter();
      release.countDown();
      reap.get(10, TimeUnit.SECONDS);
      assertTrue(cancel.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertEquals(StackUpdate.Status.FAILED, status(updateId));
  }

  private UUID startPreview(String key) {
    return start(StackUpdate.Kind.PREVIEW, key);
  }

  private UUID start(StackUpdate.Kind kind, String key) {
    StartUpdateRequest req =
        new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "desc", "yaml", "v3.234.0", "msg");
    return starter.start(stackId, kind, null, null, key, req).updateId();
  }

  // Returns once some backend is blocked on a row lock, i.e. the second transaction is waiting.
  private void awaitLockWaiter() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Number waiting =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      (Number)
                          em.createNativeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted")
                              .getSingleResult());
      if (waiting.longValue() > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("second transaction never blocked on the first");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void backdate(UUID updateId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                StackUpdate.update(
                    "startedAt = ?1 where id = ?2", Instant.now().minus(LONG_AGO), updateId));
  }

  private void expireLease() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                StackLock.update(
                    "expiresAt = ?1 where stackId = ?2", Instant.now().minusSeconds(1), stackId));
  }

  private StackUpdate.Status status(UUID updateId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> StackUpdate.<StackUpdate>findById(updateId).status);
  }

  private Instant endedAt(UUID updateId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> StackUpdate.<StackUpdate>findById(updateId).endedAt);
  }

  private UUID activeUpdateId() {
    return QuarkusTransaction.requiringNew()
        .call(() -> Stack.<Stack>findById(stackId).activeUpdateId);
  }
}
