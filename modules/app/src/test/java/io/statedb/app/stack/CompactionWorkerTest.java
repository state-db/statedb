package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.journal.JournalEntry;
import io.statedb.persistence.stack.CompactionJob;
import io.statedb.persistence.stack.CompactionJobEnqueuer;
import io.statedb.persistence.stack.CompactionWorker;
import io.statedb.persistence.stack.Snapshot;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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

    JsonNode before = normalized(assembler.assemble(stackId).deployment());

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));

    assertEquals(1, worker.drainOnce());

    JsonNode after = normalized(assembler.assemble(stackId).deployment());
    assertEquals(before, after);
    assertEquals(1L, Snapshot.count("stackId", stackId));
    assertEquals(0L, stackState(stackId).journalEntriesSinceCompaction);
    assertEquals(
        1L,
        CompactionJob.count(
            "stackId = ?1 and status = ?2", stackId, CompactionJob.Status.COMPLETED));
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

    JsonNode resources = assembler.assemble(stackId).deployment().required("resources");

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

  private CompactionJob loadCompactionJobByStack(UUID targetStackId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> CompactionJob.find("stackId", targetStackId).<CompactionJob>firstResult());
  }

  private CompactionJob loadCompactionJob(UUID jobId) {
    return QuarkusTransaction.requiringNew().call(() -> em.find(CompactionJob.class, jobId));
  }

  private JsonNode normalized(JsonNode node) {
    ObjectNode copy = (ObjectNode) node.deepCopy();
    copy.withObject("manifest").remove("time");
    return copy;
  }

  private String res(String name) {
    return "{\"urn\":\"" + urn(name) + "\",\"type\":\"pkg:index:" + name + "\",\"custom\":true}";
  }

  private String urn(String name) {
    return "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
  }
}
