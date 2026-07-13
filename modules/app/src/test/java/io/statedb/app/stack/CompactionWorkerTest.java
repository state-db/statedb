package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.journal.JournalEntry;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.CompactionJob;
import io.statedb.persistence.stack.CompactionJobEnqueuer;
import io.statedb.persistence.stack.CompactionWorker;
import io.statedb.persistence.stack.Snapshot;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.testsupport.pulumi.DeploymentNormalizer;
import io.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
class CompactionWorkerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "compaction-org";

  @Inject StackAssembler assembler;
  @Inject StackCreator stackCreator;
  @Inject CompactionJobEnqueuer enqueuer;
  @Inject CompactionWorker worker;
  @Inject EntityManager em;

  private UUID stackId;
  private int updateVersion;
  private long sequence;

  @BeforeEach
  void seed() {
    stackId = createStack("dev");
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> Organization.delete("name", ORG_NAME));
  }

  @Test
  void compactionSnapshotsCurrentDeploymentAndExportReusesIt() throws Exception {
    UUID update = update(stackId);
    entry(stackId, update, 1, 1L, "{\"state\":" + res("a") + "}");

    JsonNode before = normalized(assemble(stackId).deployment());

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));

    assertEquals(1, worker.drainOnce());

    JsonNode after = normalized(assemble(stackId).deployment());
    assertEquals(before, after);
    assertEquals(1L, Snapshot.count("stackId", stackId));
    assertEquals(0L, stackState(stackId).journalEntriesSinceCompaction);
    assertEquals(
        1L,
        CompactionJob.count(
            "stackId = ?1 and status = ?2", stackId, CompactionJob.Status.COMPLETED));
  }

  @Test
  void compactionWaitsForStackStateLockBeforeSnapshotting() throws Exception {
    UUID update = update(stackId);
    entry(stackId, update, 1, 1L, "{\"state\":" + res("locked") + "}");
    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(
                          () -> {
                            StackState.find("stackId", stackId)
                                .withLock(LockModeType.PESSIMISTIC_WRITE)
                                .<StackState>singleResult();
                            locked.countDown();
                            await(release);
                          }));

      org.junit.jupiter.api.Assertions.assertTrue(locked.await(5, TimeUnit.SECONDS));
      Future<Integer> compacting = pool.submit(() -> worker.drainOnce());

      Thread.sleep(200);
      org.junit.jupiter.api.Assertions.assertFalse(
          compacting.isDone(), "compaction must wait for the stack_state row lock");

      release.countDown();
      holder.get(5, TimeUnit.SECONDS);
      assertEquals(1, compacting.get(5, TimeUnit.SECONDS));

      StackState state = stackState(stackId);
      assertEquals(0L, state.journalEntriesSinceCompaction);
      Snapshot snapshot = Snapshot.find("stackId", stackId).<Snapshot>singleResult();
      assertEquals(state.journalSeq, snapshot.compactedFromJournalSeq);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void compactionPreservesCreatingPendingOperation() throws Exception {
    // Crash-recovery breadcrumb: a `creating` Begin without terminal must round-trip through
    // compaction so post-crash `pulumi refresh` can still see the possibly-orphan resource.
    UUID update = update(stackId);
    entry(
        stackId,
        update,
        0,
        1L,
        "{\"operation\":{\"resource\":" + res("orphan") + ",\"type\":\"creating\"}}");

    JsonNode before = normalized(assemble(stackId).deployment());
    assertEquals(1, before.required("pending_operations").size());

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));
    assertEquals(1, worker.drainOnce());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Snapshot snapshot = Snapshot.find("stackId", stackId).<Snapshot>singleResult();
              JsonNode basePending = snapshot.checkpoint.required("pending_operations");
              assertEquals(1, basePending.size());
              assertEquals("creating", basePending.get(0).required("type").asText());
              assertEquals(
                  urn("orphan"), basePending.get(0).required("resource").required("urn").asText());
            });

    JsonNode after = normalized(assemble(stackId).deployment());
    assertEquals(before, after);
  }

  @Test
  void compactionPrunesStackTraceFromSnapshot() throws Exception {
    UUID update = update(stackId);
    entry(
        stackId,
        update,
        1,
        1L,
        """
        {
          "state": {
            "urn": "%s",
            "type": "pkg:index:traced",
            "custom": true,
            "sourcePosition": "file:///work/Pulumi.yaml:12:3",
            "stackTrace": [{"sourcePosition": "file:///work/Pulumi.yaml:12:3"}]
          }
        }
        """
            .formatted(urn("traced")));

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));

    assertEquals(1, worker.drainOnce());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Snapshot snapshot = Snapshot.find("stackId", stackId).<Snapshot>singleResult();
              JsonNode resource = snapshot.checkpoint.required("resources").get(0);
              assertEquals(
                  "file:///work/Pulumi.yaml:12:3", resource.required("sourcePosition").asText());
              assertNull(resource.get("stackTrace"));
            });
  }

  @Test
  void compactionPreservesSecretsProviderAndEncryptedSecretValues() throws Exception {
    JsonNode expectedProvider =
        MAPPER.readTree("{\"type\":\"service\",\"state\":{\"url\":\"https://state-db.test\"}}");
    JsonNode expectedSecret = MAPPER.readTree(secretValue("ciphertext-from-journal"));
    UUID update = update(stackId);
    entry(stackId, update, 6, 0L, "{\"secretsProvider\":" + expectedProvider + "}");
    entry(
        stackId,
        update,
        1,
        1L,
        "{\"state\":"
            + resource(
                "stack",
                "\"type\":\"pulumi:pulumi:Stack\",\"outputs\":{\"password\":"
                    + expectedSecret
                    + "}")
            + "}");

    JsonNode before = assemble(stackId).deployment();
    assertEquals(expectedProvider, before.required("secrets_providers"));
    assertEquals(
        expectedSecret,
        before.required("resources").get(0).required("outputs").required("password"));

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));
    assertEquals(1, worker.drainOnce());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Snapshot snapshot = Snapshot.find("stackId", stackId).<Snapshot>singleResult();
              assertEquals(expectedProvider, snapshot.checkpoint.required("secrets_providers"));
              assertEquals(
                  expectedSecret,
                  snapshot
                      .checkpoint
                      .required("resources")
                      .get(0)
                      .required("outputs")
                      .required("password"));
            });

    JsonNode after = assemble(stackId).deployment();
    assertEquals(expectedProvider, after.required("secrets_providers"));
    assertEquals(
        expectedSecret,
        after.required("resources").get(0).required("outputs").required("password"));
  }

  @Test
  void assemblerStartsFromBaseSnapshotAndSkipsCompactedJournalRows() throws Exception {
    UUID oldUpdate = update(stackId);
    entry(stackId, oldUpdate, 1, 1L, "{\"state\":" + res("old") + "}");
    JsonNode baseCheckpoint = MAPPER.readTree("{\"resources\":[" + res("base") + "]}");

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Snapshot snapshot = new Snapshot();
              snapshot.stackId = stackId;
              snapshot.version = 1;
              snapshot.checkpoint = baseCheckpoint;
              snapshot.compactedFromJournalSeq = 1L;
              snapshot.persistAndFlush();

              StackState state = stackState(stackId);
              state.baseSnapshotId = snapshot.id;
              state.baseSnapshotVersion = 1;
            });

    UUID newUpdate = update(stackId);
    entry(stackId, newUpdate, 1, 2L, "{\"state\":" + res("new") + "}");

    JsonNode resources = assemble(stackId).deployment().required("resources");

    assertEquals(2, resources.size());
    assertEquals(urn("new"), resources.get(0).required("urn").asText());
    assertEquals(urn("base"), resources.get(1).required("urn").asText());
  }

  @Test
  void retryableFailureReturnsToPendingUntilMaxAttempts() throws Exception {
    UUID update = update(stackId);
    entry(stackId, update, 99, 0L, "{}");
    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));

    assertEquals(1, worker.drainOnce());

    CompactionJob job = loadCompactionJobByStack(stackId);
    assertEquals(CompactionJob.Status.PENDING, job.status);
    assertEquals(1, job.attempts);

    worker.drainOnce();
    worker.drainOnce();

    job = loadCompactionJobByStack(stackId);
    assertEquals(CompactionJob.Status.FAILED, job.status);
    assertEquals(3, job.attempts);
  }

  @Test
  void reaperReclaimsTimedOutRunningJobsAndFailsExhaustedOnes() {
    UUID retryStack = stackId;
    UUID failedStack = createStack("failed");
    UUID retryJob =
        QuarkusTransaction.requiringNew()
            .call(() -> runningJob(retryStack, 1, Instant.now().minusSeconds(600)));
    QuarkusTransaction.requiringNew()
        .call(() -> runningJob(failedStack, 3, Instant.now().minusSeconds(600)));
    UUID failedJob = loadCompactionJobByStack(failedStack).id;

    assertEquals(2, worker.reclaimStuckJobs());

    assertEquals(CompactionJob.Status.PENDING, loadCompactionJob(retryJob).status);
    assertEquals(CompactionJob.Status.FAILED, loadCompactionJob(failedJob).status);
  }

  private UUID createStack(String stackName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Organization org =
                  (Organization)
                      Organization.find("name", ORG_NAME)
                          .firstResultOptional()
                          .orElseGet(Organization::new);
              if (org.id == null) {
                org.name = ORG_NAME;
                org.displayName = "Compaction Test";
                org.persistAndFlush();
              }
              StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", stackName, Map.of());
              return ref.stackId();
            });
  }

  private UUID update(UUID targetStackId) {
    int version = ++updateVersion;
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              StackUpdate update = new StackUpdate();
              update.stackId = targetStackId;
              update.version = version;
              update.kind = StackUpdate.Kind.UPDATE;
              update.status = StackUpdate.Status.SUCCEEDED;
              update.startJournalSeq = sequence;
              update.startedAt = Instant.now();
              update.persistAndFlush();
              return update.id;
            });
  }

  private void entry(UUID targetStackId, UUID updateId, int kind, Long operationId, String payload)
      throws Exception {
    long seq = ++sequence;
    JsonNode parsed = MAPPER.readTree(payload);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              JournalEntry entry = new JournalEntry();
              entry.stackId = targetStackId;
              entry.updateId = updateId;
              entry.sequenceId = seq;
              entry.wireSequenceId = seq;
              entry.kind = (short) kind;
              entry.operationId = operationId;
              entry.payload = parsed;
              entry.persistAndFlush();

              StackState state = stackState(targetStackId);
              state.journalSeq = Math.max(state.journalSeq, seq);
              state.journalEntriesSinceCompaction++;
            });
  }

  private UUID runningJob(UUID targetStackId, int attempts, Instant startedAt) {
    CompactionJob job = new CompactionJob();
    job.stackId = targetStackId;
    job.status = CompactionJob.Status.RUNNING;
    job.attempts = attempts;
    job.startedAt = startedAt;
    job.persistAndFlush();
    return job.id;
  }

  private StackState stackState(UUID targetStackId) {
    return StackState.find("stackId", targetStackId).<StackState>firstResult();
  }

  // Compaction must be invisible to Pulumi: every export assembled in these tests — before or
  // after compaction — must pass Pulumi's own integrity rules.
  private AssembledDeployment assemble(UUID targetStackId) {
    AssembledDeployment assembled = assembler.assemble(targetStackId);
    PulumiDeploymentAssert.assertIntegrityPasses(assembled.deployment());
    return assembled;
  }

  private CompactionJob loadCompactionJobByStack(UUID targetStackId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> CompactionJob.find("stackId", targetStackId).<CompactionJob>firstResult());
  }

  private CompactionJob loadCompactionJob(UUID jobId) {
    return QuarkusTransaction.requiringNew().call(() -> em.find(CompactionJob.class, jobId));
  }

  private JsonNode normalized(JsonNode node) {
    return DeploymentNormalizer.normalize(node);
  }

  private String res(String name) {
    return "{\"urn\":\"" + urn(name) + "\",\"type\":\"pkg:index:" + name + "\",\"custom\":true}";
  }

  private String resource(String name, String extra) {
    String fields =
        "\"urn\":\"" + urn(name) + "\",\"type\":\"pkg:index:" + name + "\",\"custom\":true";
    if (!extra.isBlank()) fields += "," + extra;
    return "{" + fields + "}";
  }

  private String urn(String name) {
    return "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
  }

  private String secretValue(String ciphertext) {
    return "{\"4dabf18193072939515e22adb298388d\":\"1b47061264138c4ac30d75fd1eb44270\","
        + "\"ciphertext\":\""
        + ciphertext
        + "\"}";
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for latch");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
