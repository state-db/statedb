package app.statedb.app.stack;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.stack.CompactionJobEnqueuer;
import app.statedb.persistence.stack.CompactionWorker;
import app.statedb.persistence.stack.Snapshot;
import app.statedb.persistence.stack.SnapshotPruner;
import app.statedb.persistence.stack.SnapshotRetentionRow;
import app.statedb.persistence.stack.StackAssembler;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackImporter;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.testsupport.pulumi.DeploymentNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Fixture for the snapshot retention tests. Each concrete subclass carries its own {@code
 * state-db.compaction.prune.*} profile, because one policy cannot exercise both ladder bounds at
 * once.
 */
abstract class AbstractSnapshotPruningTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject StackAssembler assembler;
  @Inject StackCreator stackCreator;
  @Inject StackImporter importer;
  @Inject CompactionJobEnqueuer enqueuer;
  @Inject CompactionWorker worker;
  @Inject SnapshotPruner pruner;

  UUID stackId;
  int updateVersion;
  long sequence;

  /** Distinct per subclass so the profile restarts do not share fixture rows. */
  abstract String orgName();

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = orgName();
                  org.displayName = "Snapshot pruning";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, orgName(), "proj", "dev", Map.of());
                  return ref.stackId();
                });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> Organization.delete("name", orgName()));
  }

  /**
   * Runs one update end to end: journal an entry, stamp {@code end_journal_seq} the way {@link
   * app.statedb.persistence.update.StackUpdateCompleter} does, then compact. The stamp matters —
   * the update-distance ladder bound counts terminal updates.
   */
  void updateAndCompact(String resourceName) {
    int version = ++updateVersion;
    long seq = ++sequence;
    UUID updateId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  StackUpdate update = new StackUpdate();
                  update.stackId = stackId;
                  update.version = version;
                  update.kind = StackUpdate.Kind.UPDATE;
                  update.status = StackUpdate.Status.SUCCEEDED;
                  update.startJournalSeq = seq - 1;
                  update.endJournalSeq = seq;
                  update.startedAt = Instant.now();
                  update.persistAndFlush();
                  return update.id;
                });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              JournalEntry entry = new JournalEntry();
              entry.stackId = stackId;
              entry.updateId = updateId;
              entry.sequenceId = seq;
              entry.wireSequenceId = seq;
              entry.kind = (short) 1;
              entry.operationId = seq;
              entry.payload = readTree("{\"state\":" + resource(resourceName) + "}");
              entry.persistAndFlush();

              StackState state = StackState.find("stackId", stackId).<StackState>firstResult();
              state.journalSeq = Math.max(state.journalSeq, seq);
              state.journalEntriesSinceCompaction++;
            });

    compact();
  }

  /** Imports a foreign checkpoint, which installs a lineage base rather than a derived one. */
  void importDeployment(String resourceName) {
    JsonNode deployment =
        readTree("{\"resources\":[" + resource(resourceName) + "],\"pending_operations\":[]}");
    QuarkusTransaction.requiringNew()
        .run(() -> importer.importDeployment(stackId, null, null, deployment));
    updateVersion++;
  }

  void compact() {
    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));
    worker.drainOnce();
  }

  /** Re-runs retention against the settled state; a converged ladder must delete nothing. */
  int pruneAgain() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              StackState state = StackState.find("stackId", stackId).<StackState>firstResult();
              return pruner.prune(stackId, state.baseSnapshotId);
            });
  }

  CompactionJob latestJob() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                CompactionJob.<CompactionJob>find("stackId = ?1 order by createdAt desc", stackId)
                    .firstResult());
  }

  /** Compaction boundaries of the surviving snapshots, ascending. */
  List<Long> snapshotBoundaries() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Snapshot.<Snapshot>find("stackId", stackId)
                    .project(SnapshotRetentionRow.class)
                    .list()
                    .stream()
                    .map(SnapshotRetentionRow::compactedFromJournalSeq)
                    .sorted()
                    .toList());
  }

  JsonNode exportAt(long endJournalSeq, int version) {
    return DeploymentNormalizer.normalize(
        QuarkusTransaction.requiringNew()
            .call(() -> assembler.assembleAt(stackId, endJournalSeq, version).deployment()));
  }

  JsonNode exportHead() {
    return DeploymentNormalizer.normalize(
        QuarkusTransaction.requiringNew().call(() -> assembler.assemble(stackId).deployment()));
  }

  private static String resource(String name) {
    String urn = "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
    return "{\"urn\":\"" + urn + "\",\"type\":\"pkg:index:" + name + "\",\"custom\":true}";
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
