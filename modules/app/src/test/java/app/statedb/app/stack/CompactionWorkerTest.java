package app.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.stack.AssembledDeployment;
import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.stack.CompactionJobEnqueuer;
import app.statedb.persistence.stack.CompactionWorker;
import app.statedb.persistence.stack.Snapshot;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackAssembler;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.testsupport.pulumi.DeploymentNormalizer;
import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
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
  private static final String PROVIDER_URN = "urn:pulumi:dev::proj::pulumi:providers:pkg::prov";
  private static final String PROVIDER_ID = "8f1f0b1e-0000-4000-8000-000000000001";

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
    UUID jobId =
        QuarkusTransaction.requiringNew()
            .call(() -> CompactionJob.<CompactionJob>find("stackId", stackId).singleResult().id);
    assertEquals(7, jobId.version());

    assertEquals(1, worker.drainOnce());

    JsonNode after = normalized(assemble(stackId).deployment());
    assertEquals(before, after);
    assertEquals(1L, Snapshot.count("stackId", stackId));
    Snapshot snapshot = Snapshot.find("stackId", stackId).<Snapshot>singleResult();
    assertEquals(1, snapshot.resourceCount);
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
  void compactionDuringInFlightUpdateKeepsProvidersAheadOfTheirResources() throws Exception {
    // A completed update establishes the base snapshot: provider first, then its resources.
    UUID first = update(stackId);
    entry(stackId, first, 1, 1L, "{\"state\":" + providerResource() + "}");
    entry(stackId, first, 1, 2L, "{\"state\":" + providedResource("alpha") + "}");
    entry(stackId, first, 1, 3L, "{\"state\":" + providedResource("beta") + "}");

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));
    assertEquals(1, worker.drainOnce());
    assemble(stackId);

    // A second update is in flight, and only a prefix of its entries has been written.
    UUID second = update(stackId);
    markUpdateInFlight(stackId, second);
    entryReplacingBase(stackId, second, 4L, "{\"state\":" + providerResource() + "}", 0L);
    entryReplacingBase(stackId, second, 5L, "{\"state\":" + providedResource("alpha") + "}", 1L);

    // Compacting the prefix would put the suffix ahead of the provider on the next replay.
    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));
    worker.drainOnce();

    assertEquals(1L, Snapshot.count("stackId", stackId), "no snapshot may be taken mid-update");
    assertEquals(
        1L,
        CompactionJob.count("stackId = ?1 and status = ?2", stackId, CompactionJob.Status.PENDING),
        "the job must be handed back as pending, not consumed");

    // The rest lands and the update completes.
    entryReplacingBase(stackId, second, 6L, "{\"state\":" + providedResource("beta") + "}", 2L);
    completeUpdate(stackId);
    assertEquals(1, worker.drainOnce());

    assertEquals(2L, Snapshot.count("stackId", stackId));
    assemble(stackId);
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
        MAPPER.readTree("{\"type\":\"service\",\"state\":{\"url\":\"https://statedb.test\"}}");
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

  /** Journals an entry that supersedes a base-snapshot resource at {@code removeOldIndex}. */
  private void entryReplacingBase(
      UUID targetStackId, UUID updateId, Long operationId, String payload, long removeOldIndex)
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
              entry.kind = (short) 1;
              entry.operationId = operationId;
              entry.removeOld = removeOldIndex;
              entry.payload = parsed;
              entry.persistAndFlush();

              StackState state = stackState(targetStackId);
              state.journalSeq = Math.max(state.journalSeq, seq);
              state.journalEntriesSinceCompaction++;
            });
  }

  /** Clears the in-flight marker, as update completion does. */
  private void completeUpdate(UUID targetStackId) {
    QuarkusTransaction.requiringNew()
        .run(() -> Stack.update("activeUpdateId = null where id = ?1", targetStackId));
  }

  /** Marks the stack as having an update in flight, as lock acquisition does. */
  private void markUpdateInFlight(UUID targetStackId, UUID updateId) {
    QuarkusTransaction.requiringNew()
        .run(() -> Stack.update("activeUpdateId = ?1 where id = ?2", updateId, targetStackId));
  }

  private String providerResource() {
    return "{\"urn\":\""
        + PROVIDER_URN
        + "\",\"type\":\"pulumi:providers:pkg\",\"custom\":true,\"id\":\""
        + PROVIDER_ID
        + "\"}";
  }

  private String providedResource(String name) {
    return "{\"urn\":\""
        + urn(name)
        + "\",\"type\":\"pkg:index:"
        + name
        + "\",\"custom\":true,\"provider\":\""
        + PROVIDER_URN
        + "::"
        + PROVIDER_ID
        + "\"}";
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
