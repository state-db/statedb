package app.statedb.app.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateCompleter;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import app.statedb.persistence.update.StartedUpdate;
import app.statedb.persistence.vcs.ReportDelivery;
import app.statedb.persistence.vcs.ReportDeliveryWorker;
import app.statedb.persistence.vcs.VcsConnection;
import app.statedb.testsupport.InMemoryVcs;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReportDeliveryLifecycleTest {

  private static final String ORG_NAME = "report-delivery-org";
  private static final String HOST = "github.test";
  private static final String OWNER = "acme";
  private static final String REPOSITORY = "infra";
  private static final String REPOSITORY_ID = "repo-42";
  private static final String HEAD_1 = "1111111111111111111111111111111111111111";
  private static final String HEAD_2 = "2222222222222222222222222222222222222222";

  @Inject EntityManager em;
  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer lockAcquirer;
  @Inject StackUpdateCompleter completer;
  @Inject StackLockCanceller canceller;
  @Inject ReportDeliveryWorker worker;
  @Inject InMemoryVcs vcs;

  private StackRef stack;

  @BeforeEach
  void seed() {
    vcs.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Report delivery";
              org.persistAndFlush();
              stack = stackCreator.create(org.id, ORG_NAME, "project", "dev", Map.of());

              VcsConnection connection = new VcsConnection();
              connection.orgId = org.id;
              connection.kind = "memory";
              connection.host = HOST;
              connection.persistAndFlush();
            });
  }

  @AfterEach
  void cleanup() {
    vcs.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());
  }

  @Test
  void enqueuesOnlyWhenTheRunEnds() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running run = running("waiting", HEAD_1);
    assertEquals(0, ReportDelivery.count("updateId", run.update().updateId()));
    assertEquals(0, worker.deliverOnce());
    complete(run);
    assertEquals(1, worker.deliverOnce());
    assertEquals("delivered", delivery(run.update().updateId()).state);
    assertTrue(vcs.comments().getFirst().body.contains("http://statedb.test/console/"));
  }

  @Test
  void allTerminalPathsUseTheSameQueue() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running completed = running("complete", HEAD_1);
    complete(completed);
    Running graceful = running("graceful", HEAD_1);
    canceller.gracefulCancel(
        graceful.lease().leaseId(), stack.stackId(), graceful.update().updateId());
    Running forced = running("forced", HEAD_1);
    assertTrue(canceller.forceCancelUpdate(stack.stackId(), forced.update().updateId()));
    Running unlocked = running("unlocked", HEAD_1);
    assertTrue(canceller.forceCancelLock(stack.stackId(), unlocked.lease().leaseId()));
    assertEquals(4, worker.deliverOnce());
    assertEquals(4, vcs.comments().size());
    assertEquals(1, vcs.comments().stream().filter(c -> !c.minimized).count());
  }

  @Test
  void retryAfterRemotePostKeepsBodyAndFrozenTargetEvenAfterHeadChanges() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running first = running("first", HEAD_1);
    complete(first);
    assertEquals(1, worker.deliverOnce());
    String original = vcs.comments().getFirst().body;
    Running next = running("next", HEAD_1);
    complete(next);
    vcs.failAfterNextPost();
    assertEquals(0, worker.deliverOnce());
    ReportDelivery pending = delivery(next.update().updateId());
    assertEquals("pending", pending.state);
    assertEquals(1, pending.attempts);
    assertTrue(pending.body != null);
    assertEquals(2, vcs.comments().size());
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_2, false);
    due(next.update().updateId());
    assertEquals(1, worker.deliverOnce());
    assertEquals(2, vcs.comments().size());
    assertTrue(vcs.comments().getFirst().minimized);
    assertEquals(original, vcs.comments().getFirst().body);
  }

  @Test
  void definitiveFailureDoesNotRetry() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running run = running("forbidden", HEAD_1);
    complete(run);
    vcs.forbidPublication();
    assertEquals(0, worker.deliverOnce());
    ReportDelivery row = delivery(run.update().updateId());
    assertEquals("failed", row.state);
    assertEquals("permission denied", row.lastError);
    due(run.update().updateId());
    worker.deliverOnce();
    assertEquals(1, delivery(run.update().updateId()).attempts);
  }

  @Test
  void unmatchedCommitFailsDuringPreparationWithoutPosting() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_2, true);
    Running run = running("unmatched", HEAD_1);
    complete(run);
    assertEquals(0, worker.deliverOnce());
    ReportDelivery row = delivery(run.update().updateId());
    assertEquals("failed", row.state);
    assertEquals("no unique open PR matching the run commit", row.lastError);
    assertNull(row.body);
    assertTrue(vcs.comments().isEmpty());
  }

  @Test
  void disconnectAfterEnqueueFailsWithoutPublication() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running run = running("disconnected", HEAD_1);
    complete(run);
    QuarkusTransaction.requiringNew()
        .run(() -> VcsConnection.update("enabled = false where host = ?1", HOST));
    assertEquals(0, worker.deliverOnce());
    ReportDelivery row = delivery(run.update().updateId());
    assertEquals("failed", row.state);
    assertEquals("connection is disabled or deleted", row.lastError);
    assertTrue(vcs.comments().isEmpty());
  }

  @Test
  void missingCommitDoesNotAllocateAddonStorage() {
    Running run = running("no-commit", null);
    complete(run);
    assertEquals(0, ReportDelivery.count("updateId", run.update().updateId()));
  }

  @Test
  void orgWithoutConnectionCompletesWithoutQueueing() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                VcsConnection.update(
                    "enabled = false where orgId = ?1",
                    Organization.<Organization>find("name", ORG_NAME).singleResult().id));
    Running run = running("no-connection", HEAD_1);
    complete(run);
    assertEquals(StackUpdate.Status.SUCCEEDED, status(run.update().updateId()));
    assertEquals(0, ReportDelivery.count("updateId", run.update().updateId()));
  }

  @Test
  void concurrentWorkersPublishOneComment() throws Exception {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running run = running("concurrent", HEAD_1);
    complete(run);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(worker::deliverOnce);
      Future<Integer> b = pool.submit(worker::deliverOnce);
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
      assertEquals(1, vcs.comments().size());
      assertEquals("delivered", delivery(run.update().updateId()).state);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void retentionDeletesOnlySettledAddonRows() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running run = running("retention", HEAD_1);
    complete(run);
    worker.deliverOnce();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReportDelivery.update(
                    "settledAt = ?1 where updateId = ?2", Instant.EPOCH, run.update().updateId()));
    worker.deliverOnce();
    assertEquals(0, ReportDelivery.count("updateId", run.update().updateId()));
    assertTrue(StackUpdate.findById(run.update().updateId()) != null);
  }

  @Test
  void lostDatabaseCommitReconcilesAlreadyPublishedComment() {
    vcs.addPullRequest(REPOSITORY_ID, 17, HEAD_1, true);
    Running run = running("commit-lost", HEAD_1);
    complete(run);
    worker.deliverOnce();
    String commentId = vcs.comments().getFirst().id;
    // Simulate rollback of the final database transaction after GitHub accepted its writes.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReportDelivery.update(
                    "state = 'pending', externalId = null, settledAt = null, nextAttemptAt = ?1"
                        + " where updateId = ?2",
                    Instant.EPOCH,
                    run.update().updateId()));
    assertEquals(1, worker.deliverOnce());
    assertEquals(1, vcs.comments().size());
    assertEquals(commentId, delivery(run.update().updateId()).externalId);
  }

  @Test
  void leaseTagsAreReadAtCompletionAndEnqueueIsIdempotent() {
    StartedUpdate run =
        starter.start(
            stack.stackId(),
            StackUpdate.Kind.PREVIEW,
            null,
            null,
            "lease-tags",
            new StartUpdateRequest(
                Map.of(),
                Map.of("environment", Map.of("git.head", HEAD_1)),
                Map.of(),
                null,
                "yaml",
                null,
                "lease-tags"));
    Lease lease =
        lockAcquirer
            .acquire(
                stack.stackId(),
                run.updateId(),
                "test",
                null,
                Map.of("vcs:kind", HOST, "vcs:owner", OWNER, "vcs:repo", REPOSITORY))
            .orElseThrow();
    assertEquals(0, ReportDelivery.count("updateId", run.updateId()));
    complete(new Running(run, lease));
    QuarkusTransaction.requiringNew()
        .run(() -> worker.enqueue(StackUpdate.findById(run.updateId())));
    assertEquals(1, ReportDelivery.count("updateId", run.updateId()));
    assertEquals(REPOSITORY, delivery(run.updateId()).repositoryName);
  }

  private void complete(Running run) {
    completer.complete(
        run.lease().leaseId(),
        stack.stackId(),
        run.update().updateId(),
        StackUpdate.Status.SUCCEEDED);
  }

  private void due(UUID update) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReportDelivery.update(
                    "nextAttemptAt = ?1 where updateId = ?2", Instant.EPOCH, update));
  }

  private StackUpdate.Status status(UUID update) {
    em.clear();
    return StackUpdate.<StackUpdate>findById(update).status;
  }

  private ReportDelivery delivery(UUID update) {
    em.clear();
    return ReportDelivery.<ReportDelivery>find("updateId", update).singleResult();
  }

  private Running running(String key, String head) {
    StartedUpdate update = start(key, head, 17L);
    Lease lease =
        lockAcquirer.acquire(stack.stackId(), update.updateId(), "test", null, null).orElseThrow();
    return new Running(update, lease);
  }

  private StartedUpdate start(String key, String head, Long prNumber) {
    Map<String, Object> environment = new java.util.HashMap<>();
    if (head != null) {
      environment.put("ci.pr.headSHA", head);
    }
    if (prNumber != null) {
      environment.put("ci.pr.number", Long.toString(prNumber));
    }
    StartedUpdate started =
        starter.start(
            stack.stackId(),
            StackUpdate.Kind.PREVIEW,
            null,
            null,
            key,
            new StartUpdateRequest(
                Map.of(), Map.of("environment", environment), Map.of(), null, "yaml", null, key));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackUpdate update = StackUpdate.findById(started.updateId());
              update.tags = Map.of("vcs:kind", HOST, "vcs:owner", OWNER, "vcs:repo", REPOSITORY);
            });
    return started;
  }

  private record Running(StartedUpdate update, Lease lease) {}
}
