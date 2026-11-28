package app.statedb.app.update;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.statedb.app.DatabaseTest;
import app.statedb.app.Races;
import app.statedb.app.TestDb;
import app.statedb.persistence.events.EventBatchAppender;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.journal.JournalAppender;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.lock.StackLockedException;
import app.statedb.persistence.stack.AssembledDeployment;
import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackAssembler;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateCompleter;
import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@QuarkusTest
class StackUpdateCompleterTest extends DatabaseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "completer-org";

  @Inject StackUpdateCompleter completer;
  @Inject JournalAppender journalAppender;
  @Inject EventBatchAppender eventBatchAppender;
  @Inject StackAssembler assembler;

  private TestDb.ActiveUpdate seed(StackUpdate.Kind kind) {
    UUID stackId = db.seedStack(ORG_NAME).stackId();
    return db.seedActiveUpdate(stackId, kind);
  }

  private void bumpJournalSeq(UUID stackId, long to) {
    requiringNew()
        .run(
            () -> {
              StackState state = StackState.find("stackId", stackId).<StackState>singleResult();
              state.journalSeq = to;
            });
  }

  @Test
  void succeededUpdateReleasesLockBumpsVersionAndStampsEndSeq() {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);
    bumpJournalSeq(f.stackId(), 42L);

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(0L, count("SELECT count(*) FROM stack_locks WHERE stack_id = ?1", f.stackId()));
    assertEquals(
        "succeeded", scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(
        42L, scalarLong("SELECT end_journal_seq FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(1L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
    assertNull(scalarUuid("SELECT active_update_id FROM stacks WHERE id = ?1", f.stackId()));
  }

  @Test
  void completeEnqueuesOneActiveCompactionJobPerStack() {
    TestDb.ActiveUpdate first = seed(StackUpdate.Kind.UPDATE);

    completer.complete(
        first.leaseId(), first.stackId(), first.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(
        1L,
        CompactionJob.count(
            "stackId = ?1 and status = ?2", first.stackId(), CompactionJob.Status.PENDING));

    TestDb.ActiveUpdate second = db.seedActiveUpdate(first.stackId(), StackUpdate.Kind.UPDATE);

    completer.complete(
        second.leaseId(), second.stackId(), second.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(
        1L,
        CompactionJob.count(
            "stackId = ?1 and status = ?2", first.stackId(), CompactionJob.Status.PENDING));
  }

  @Test
  void succeededPreviewLeavesStackVersionUnchanged() {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.PREVIEW);
    bumpJournalSeq(f.stackId(), 7L);

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(0L, count("SELECT count(*) FROM stack_locks WHERE stack_id = ?1", f.stackId()));
    assertEquals(
        "succeeded", scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(
        7L, scalarLong("SELECT end_journal_seq FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(0L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
    assertNull(scalarUuid("SELECT active_update_id FROM stacks WHERE id = ?1", f.stackId()));
  }

  @Test
  void previewPersistsEventsButDoesNotChangeCurrentExport() throws Exception {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.PREVIEW);

    eventBatchAppender.append(
        f.stackId(),
        f.updateId(),
        f.leaseId(),
        List.of(event(1, "diagnosticEvent", "{\"severity\":\"info\",\"message\":\"planning\"}")));

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(1L, UpdateEvent.count("updateId", f.updateId()));
    assertEquals(0L, JournalEntry.count("updateId", f.updateId()));
    assertEquals(0, stack(f.stackId()).version);
    assertEquals(0, assemble(f.stackId()).deployment().required("resources").size());
  }

  @ParameterizedTest
  @EnumSource(
      value = StackUpdate.Kind.class,
      names = {"UPDATE", "REFRESH", "DESTROY", "IMPORT"})
  void mutatingKindsAdvanceVersionAndStampTerminalBoundary(StackUpdate.Kind kind) {
    TestDb.ActiveUpdate f = seed(kind);
    bumpJournalSeq(f.stackId(), 13L);

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

    StackUpdate update = update(f.updateId());
    assertEquals(StackUpdate.Status.SUCCEEDED, update.status);
    assertEquals(13L, update.endJournalSeq);
    assertEquals(1, stack(f.stackId()).version);
    assertEquals(0L, StackLock.count("stackId", f.stackId()));
    assertNull(stack(f.stackId()).activeUpdateId);
  }

  @Test
  void failedUpdateStillBumpsVersion() {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.FAILED);

    assertEquals(
        "failed", scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(1L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
  }

  @Test
  void failedUpdateWithNoSuccessfulResourcesLeavesExportUnchanged() throws Exception {
    TestDb.ActiveUpdate succeeded = seed(StackUpdate.Kind.UPDATE);
    journalAppender.append(
        succeeded.stackId(),
        succeeded.updateId(),
        succeeded.leaseId(),
        List.of(journalEntry(1, 1, 1L, "{\"state\":" + resource("base") + "}")));
    completer.complete(
        succeeded.leaseId(),
        succeeded.stackId(),
        succeeded.updateId(),
        StackUpdate.Status.SUCCEEDED);

    TestDb.ActiveUpdate failed = db.seedActiveUpdate(succeeded.stackId(), StackUpdate.Kind.UPDATE);
    journalAppender.append(
        failed.stackId(),
        failed.updateId(),
        failed.leaseId(),
        List.of(
            journalEntry(
                1,
                0,
                9L,
                "{\"operation\":{\"resource\":"
                    + resource("interrupted")
                    + ",\"type\":\"creating\"}}"),
            journalEntry(2, 2, 9L, "{}")));

    completer.complete(
        failed.leaseId(), failed.stackId(), failed.updateId(), StackUpdate.Status.FAILED);

    StackUpdate failedUpdate = update(failed.updateId());
    assertEquals(StackUpdate.Status.FAILED, failedUpdate.status);
    assertEquals(0L, StackLock.count("stackId", failed.stackId()));
    assertEquals(1, assemble(failed.stackId()).deployment().required("resources").size());
    assertEquals(
        urn("base"),
        assembler
            .assemble(failed.stackId())
            .deployment()
            .required("resources")
            .get(0)
            .required("urn")
            .asText());
  }

  @Test
  void failedUpdateKeepsSuccessfulJournalEntriesInExport() throws Exception {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);
    journalAppender.append(
        f.stackId(),
        f.updateId(),
        f.leaseId(),
        List.of(
            journalEntry(1, 1, 1L, "{\"state\":" + resource("partial") + "}"),
            journalEntry(
                2,
                0,
                9L,
                "{\"operation\":{\"resource\":"
                    + resource("interrupted")
                    + ",\"type\":\"creating\"}}"),
            journalEntry(3, 2, 9L, "{}")));

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.FAILED);

    StackUpdate failedUpdate = update(f.updateId());
    assertEquals(StackUpdate.Status.FAILED, failedUpdate.status);
    assertEquals(0L, StackLock.count("stackId", f.stackId()));

    JsonNode resources = assemble(f.stackId()).deployment().required("resources");
    assertEquals(1, resources.size());
    assertEquals(urn("partial"), resources.get(0).required("urn").asText());
  }

  @Test
  void completeWaitsForStackStateLockBeforeStampingBoundary() throws Exception {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);
    Races.holdThenContend(
        em,
        () -> {
          StackState state =
              StackState.find("stackId", f.stackId())
                  .withLock(LockModeType.PESSIMISTIC_WRITE)
                  .<StackState>singleResult();
          state.journalSeq = 21;
        },
        "%stack_state%",
        () ->
            completer.complete(
                f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED));

    assertEquals(21L, update(f.updateId()).endJournalSeq);
  }

  @Test
  void wrongLeaseIdThrowsAndLeavesLockIntact() {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);
    UUID wrong = UUID.randomUUID();

    assertThrows(
        StackLockedException.class,
        () -> completer.complete(wrong, f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED));

    assertEquals(1L, count("SELECT count(*) FROM stack_locks WHERE stack_id = ?1", f.stackId()));
    assertEquals(
        "in-progress",
        scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(0L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
    assertNotNull(scalarUuid("SELECT active_update_id FROM stacks WHERE id = ?1", f.stackId()));
  }

  @Test
  void forcedFailureMidTxRollsBackAllThreeRows() {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);
    bumpJournalSeq(f.stackId(), 99L);

    // completer.complete is @Transactional(REQUIRED) so it joins this outer tx; throwing here
    // marks the outer tx for rollback and the lock DELETE / status flip / version bump all unwind.
    assertThrows(
        RuntimeException.class,
        () ->
            requiringNew()
                .run(
                    () -> {
                      completer.complete(
                          f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);
                      throw new RuntimeException("forced rollback");
                    }));

    assertEquals(1L, count("SELECT count(*) FROM stack_locks WHERE stack_id = ?1", f.stackId()));
    assertEquals(
        "in-progress",
        scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertNull(
        scalarLongOrNull("SELECT end_journal_seq FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(0L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
    assertNotNull(scalarUuid("SELECT active_update_id FROM stacks WHERE id = ?1", f.stackId()));
  }

  @Test
  void completeOnAlreadyTerminalUpdateReleasesLockButLeavesStatusAndVersion() {
    TestDb.ActiveUpdate f = seed(StackUpdate.Kind.UPDATE);
    // Simulate a concurrent cancel: status flipped, active_update_id cleared, lock still present
    // (in the real cancel path the lock is gone too — here we test the "lock + terminal status"
    // window, which is what makes this branch worth defending).
    requiringNew()
        .run(
            () -> {
              em.createNativeQuery(
                      "UPDATE stack_updates SET status = 'cancelled', ended_at = now()"
                          + " WHERE id = ?1")
                  .setParameter(1, f.updateId())
                  .executeUpdate();
              em.createNativeQuery("UPDATE stacks SET active_update_id = NULL WHERE id = ?1")
                  .setParameter(1, f.stackId())
                  .executeUpdate();
            });

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(0L, count("SELECT count(*) FROM stack_locks WHERE stack_id = ?1", f.stackId()));
    assertEquals(
        "cancelled", scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(0L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
  }

  private long count(String sql, Object param) {
    return scalarLong(sql, param);
  }

  private long scalarLong(String sql, Object param) {
    Number n = (Number) em.createNativeQuery(sql).setParameter(1, param).getSingleResult();
    return n.longValue();
  }

  private String scalarString(String sql, Object param) {
    return (String) em.createNativeQuery(sql).setParameter(1, param).getSingleResult();
  }

  // Exports assembled around update completion boundaries must pass Pulumi's integrity rules.
  private AssembledDeployment assemble(UUID stackId) {
    AssembledDeployment assembled = assembler.assemble(stackId);
    PulumiDeploymentAssert.assertIntegrityPasses(assembled.deployment());
    return assembled;
  }

  private UUID scalarUuid(String sql, Object param) {
    return (UUID) em.createNativeQuery(sql).setParameter(1, param).getSingleResult();
  }

  private Long scalarLongOrNull(String sql, Object param) {
    Object o = em.createNativeQuery(sql).setParameter(1, param).getSingleResult();
    if (o == null) return null;
    return ((Number) o).longValue();
  }

  private Stack stack(UUID stackId) {
    return requiringNew().call(() -> Stack.findById(stackId));
  }

  private StackUpdate update(UUID updateId) {
    return requiringNew().call(() -> StackUpdate.findById(updateId));
  }

  private static JsonNode event(int sequence, String kind, String payloadJson) throws Exception {
    JsonNode payload = MAPPER.readTree(payloadJson);
    return MAPPER.createObjectNode().put("sequence", sequence).set(kind, payload);
  }

  private static JsonNode journalEntry(long wireSeq, int kind, Long operationId, String payloadJson)
      throws Exception {
    JsonNode payload = MAPPER.readTree(payloadJson);
    var node =
        MAPPER.createObjectNode().put("sequenceID", wireSeq).put("kind", kind).put("version", 1);
    if (operationId != null) node.put("operationID", operationId);
    if (payload.has("state")) node.set("state", payload.required("state"));
    if (payload.has("operation")) node.set("operation", payload.required("operation"));
    return node;
  }

  private static String resource(String name) {
    return "{\"urn\":\"" + urn(name) + "\",\"type\":\"pkg:index:" + name + "\",\"custom\":true}";
  }

  private static String urn(String name) {
    return "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
  }
}
