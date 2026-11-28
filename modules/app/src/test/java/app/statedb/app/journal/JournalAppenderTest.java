package app.statedb.app.journal;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.journal.JournalAppender;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.journal.JournalSequenceConflictException;
import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.stack.AssembledDeployment;
import app.statedb.persistence.stack.StackAssembler;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JournalAppenderTest extends DatabaseTest {

  private static final String ORG_NAME = "journal-org";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject StackLockCanceller lockCanceller;
  @Inject JournalAppender appender;
  @Inject StackAssembler assembler;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;

  @BeforeEach
  void seed() {
    TestDb.ActiveUpdate active =
        db.seedActiveUpdate(db.seedStack(ORG_NAME).stackId(), StackUpdate.Kind.UPDATE);
    stackId = active.stackId();
    updateId = active.updateId();
    leaseId = active.leaseId();
  }

  @Test
  void singleBatchPersistsOffsetAndRows() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(
                1,
                0,
                1L,
                "{\"operation\":{\"resource\":{\"urn\":\"urn:a\",\"type\":\"t:a\"},\"type\":\"creating\"}}"),
            entry(
                2,
                0,
                2L,
                "{\"operation\":{\"resource\":{\"urn\":\"urn:b\",\"type\":\"t:b\"},\"type\":\"creating\"}}"),
            entry(3, 1, 1L, "{\"state\":{\"urn\":\"urn:a\"}}")));

    long offset =
        ((Number)
                em.createNativeQuery("SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
                    .setParameter(1, updateId)
                    .getSingleResult())
            .longValue();
    assertEquals(0L, offset);

    List<?> rows =
        em.createNativeQuery(
                "SELECT sequence_id, wire_sequence_id, kind, resource_urn "
                    + "FROM journal_entries WHERE update_id = ?1 ORDER BY sequence_id")
            .setParameter(1, updateId)
            .getResultList();
    assertEquals(3, rows.size());

    long stateSeq =
        ((Number)
                em.createNativeQuery("SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(4L, stateSeq);
  }

  @Test
  void crossStackUpdateIdIsNotReportedAsASequenceConflict() {
    UUID otherStackId = db.seedStack(ORG_NAME, "proj", "other").stackId();

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> appender.append(otherStackId, updateId, leaseId, List.of(entry(1, 1, 1L, "{}"))));
    assertFalse(thrown instanceof JournalSequenceConflictException);
    String sqlState = null;
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException cve && cve.getSQLException() != null) {
        sqlState = cve.getSQLException().getSQLState();
        break;
      }
    }
    assertEquals("23503", sqlState);
  }

  @Test
  void dryRunUpdateCannotAppendAuthoritativeJournalEntries() {
    requiringNew()
        .run(
            () -> {
              StackUpdate update = StackUpdate.findById(updateId);
              update.dryRun = true;
            });

    RequestValidationException error =
        assertThrows(
            RequestValidationException.class,
            () ->
                appender.append(
                    stackId,
                    updateId,
                    leaseId,
                    List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:a\"}}"))));
    assertTrue(error.getMessage().contains("dry-run"));
  }

  @Test
  void missingUpdateCannotAppendJournalEntries() {
    RequestValidationException error =
        assertThrows(
            RequestValidationException.class,
            () ->
                appender.append(
                    stackId,
                    UUID.randomUUID(),
                    leaseId,
                    List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:a\"}}"))));
    assertEquals("update does not exist", error.getMessage());
  }

  @Test
  void outOfOrderWireIdsWithinBatchReplayCorrectly() {
    // Entries arrive [6, 5, 7]; replay by sequence_id should be [5, 6, 7].
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(6, 1, 6L, "{\"state\":{\"urn\":\"urn:6\"}}"),
            entry(5, 1, 5L, "{\"state\":{\"urn\":\"urn:5\"}}"),
            entry(7, 1, 7L, "{\"state\":{\"urn\":\"urn:7\"}}")));

    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                "SELECT wire_sequence_id, resource_urn FROM journal_entries "
                    + "WHERE update_id = ?1 ORDER BY sequence_id")
            .setParameter(1, updateId)
            .getResultList();
    assertEquals(3, rows.size());
    assertEquals(5L, ((Number) rows.get(0)[0]).longValue());
    assertEquals(6L, ((Number) rows.get(1)[0]).longValue());
    assertEquals(7L, ((Number) rows.get(2)[0]).longValue());
    assertEquals("urn:5", rows.get(0)[1]);
  }

  @Test
  void outOfOrderAcrossPatchesReplayCorrectly() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(3, 1, 3L, "{\"state\":{\"urn\":\"urn:3\"}}"),
            entry(5, 1, 5L, "{\"state\":{\"urn\":\"urn:5\"}}")));
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(4, 1, 4L, "{\"state\":{\"urn\":\"urn:4\"}}"),
            entry(6, 1, 6L, "{\"state\":{\"urn\":\"urn:6\"}}")));

    @SuppressWarnings("unchecked")
    List<Number> wireSeqs =
        em.createNativeQuery(
                "SELECT wire_sequence_id FROM journal_entries WHERE update_id = ?1 "
                    + "ORDER BY sequence_id")
            .setParameter(1, updateId)
            .getResultList();
    assertEquals(List.of(3L, 4L, 5L, 6L), wireSeqs.stream().map(Number::longValue).toList());

    long stateSeq =
        ((Number)
                em.createNativeQuery("SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(7L, stateSeq);
  }

  @Test
  void duplicateIdenticalPatchIsNoOp() {
    appender.append(
        stackId, updateId, leaseId, List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\"}}")));
    appender.append(
        stackId, updateId, leaseId, List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\"}}")));

    assertEquals(1L, JournalEntry.count("updateId", updateId));

    long stateSeq =
        ((Number)
                em.createNativeQuery("SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(2L, stateSeq);

    long sinceCompaction =
        ((Number)
                em.createNativeQuery(
                        "SELECT journal_entries_since_compaction FROM stack_state "
                            + "WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(1L, sinceCompaction);
  }

  @Test
  void partialOverlapRetryInsertsOnlyMissingEntries() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\"}}"),
            entry(2, 1, 2L, "{\"state\":{\"urn\":\"urn:2\"}}")));
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(2, 1, 2L, "{\"state\":{\"urn\":\"urn:2\"}}"),
            entry(3, 1, 3L, "{\"state\":{\"urn\":\"urn:3\"}}")));

    assertEquals(3L, JournalEntry.count("updateId", updateId));

    long stateSeq =
        ((Number)
                em.createNativeQuery("SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(4L, stateSeq);

    long sinceCompaction =
        ((Number)
                em.createNativeQuery(
                        "SELECT journal_entries_since_compaction FROM stack_state "
                            + "WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(3L, sinceCompaction);
  }

  @Test
  void duplicateJsonbEquivalentPayloadIsNoOp() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\",\"value\":1.0}}")));
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\",\"value\":1}}")));

    assertEquals(1L, JournalEntry.count("updateId", updateId));
  }

  @Test
  void duplicateWireSequenceWithDifferentPayloadReturns409() {
    appender.append(
        stackId, updateId, leaseId, List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\"}}")));
    assertThrows(
        JournalSequenceConflictException.class,
        () ->
            appender.append(
                stackId,
                updateId,
                leaseId,
                List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:dup\"}}"))));
  }

  @Test
  void invalidKindRejectedWithNoWrites() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, List.of(entry(1, 8, null, "{}"))));

    assertEquals(0L, JournalEntry.count("updateId", updateId));
  }

  @Test
  void nonPositiveSequenceRejected() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, List.of(entry(0, 0, 1L, "{}"))));
  }

  @Test
  void successBeforeBeginArrivalStillReplaysInSequenceOrder() {
    // Batch 1 carries a terminal Success for op 42; batch 2 carries its Begin with an earlier wire
    // sequence. Arrival order must not affect the reconstructed deployment.
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(entry(2, 1, 42L, "{\"state\":{\"urn\":\"urn:x\",\"type\":\"t:x\"}}")));
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(
                1,
                0,
                42L,
                "{\"operation\":{\"resource\":{\"urn\":\"urn:x\",\"type\":\"t:x\"},"
                    + "\"type\":\"creating\"}}")));

    AssembledDeployment assembled = assemble(stackId);
    assertFalse(assembled.deployment().has("pending_operations"));
  }

  @Test
  void unknownOperationTypeIsPersistedAndReplayed() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(
                1,
                0,
                7L,
                "{\"operation\":{\"resource\":{\"urn\":\"urn:r\",\"type\":\"t:x\"},\"type\":\"bogus\"}}")));

    assertEquals(1L, JournalEntry.count("updateId", updateId));

    JsonNode pending = assemble(stackId).deployment().required("pending_operations");
    assertEquals(1, pending.size());
    assertEquals("bogus", pending.get(0).required("type").asText());
    assertEquals("urn:r", pending.get(0).required("resource").required("urn").asText());
  }

  @Test
  void payloadStoredWithoutPromotedFields() throws Exception {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(1, 3, 9L, "{\"state\":{\"urn\":\"urn:s\"},\"isRefresh\":true,\"removeOld\":5}")));

    String payloadJson =
        (String)
            em.createNativeQuery("SELECT payload::text FROM journal_entries WHERE update_id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    JsonNode payload = MAPPER.readTree(payloadJson);
    // state remains in payload; promoted columns are removed.
    assertNotNull(payload.get("state"));
    assertNull(payload.get("isRefresh"));
    assertNull(payload.get("removeOld"));
    assertNull(payload.get("sequenceID"));
    assertNull(payload.get("kind"));

    Boolean isRefresh =
        (Boolean)
            em.createNativeQuery("SELECT is_refresh FROM journal_entries WHERE update_id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    assertTrue(isRefresh);

    Number removeOld =
        (Number)
            em.createNativeQuery("SELECT remove_old FROM journal_entries WHERE update_id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    assertEquals(5L, removeOld.longValue());
  }

  @Test
  void payloadPrunesStackTraceButKeepsSourcePosition() throws Exception {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(
                1,
                1,
                1L,
                """
                {
                  "state": {
                    "urn": "urn:s",
                    "sourcePosition": "file:///work/Pulumi.yaml:12:3",
                    "stackTrace": [
                      {"sourcePosition": "file:///work/Pulumi.yaml:12:3"},
                      {"sourcePosition": "file:///work/Pulumi.yaml:1:1"}
                    ],
                    "outputs": {
                      "nested": {
                        "stackTrace": [{"sourcePosition": "file:///work/lib.ts:1:1"}]
                      }
                    }
                  }
                }
                """)));

    String payloadJson =
        (String)
            em.createNativeQuery("SELECT payload::text FROM journal_entries WHERE update_id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    JsonNode payload = MAPPER.readTree(payloadJson);
    JsonNode state = payload.required("state");
    assertEquals("file:///work/Pulumi.yaml:12:3", state.required("sourcePosition").asText());
    assertNull(state.get("stackTrace"));
    assertNull(state.required("outputs").required("nested").get("stackTrace"));
  }

  @Test
  void concurrentAppendsSerializeAndPreserveIntegrity() throws Exception {
    // Two PATCHes for the same update arriving on different threads/pods. FOR UPDATE on
    // stack_state must serialize them so both succeed and all rows persist.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CyclicBarrier start = new CyclicBarrier(2);
    try {
      Future<?> f1 =
          pool.submit(
              (Runnable)
                  () -> {
                    try {
                      start.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                    appender.append(
                        stackId,
                        updateId,
                        leaseId,
                        List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"u:1\"}}")));
                  });
      Future<?> f2 =
          pool.submit(
              (Runnable)
                  () -> {
                    try {
                      start.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                    appender.append(
                        stackId,
                        updateId,
                        leaseId,
                        List.of(entry(2, 1, 2L, "{\"state\":{\"urn\":\"u:2\"}}")));
                  });
      f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);

      assertEquals(2L, JournalEntry.count("updateId", updateId));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void appendAfterLockDeletedThrowsLeaseExpired() {
    // Simulate /complete (or a cancel) winning the race: lock row is gone before the appender's
    // tx starts. Without in-tx revalidation, append would advance journal_seq past
    // end_journal_seq; with it, requireLive throws and zero rows persist.
    lockCanceller.gracefulCancel(leaseId, stackId, updateId);

    assertThrows(
        LeaseExpiredException.class,
        () ->
            appender.append(
                stackId,
                updateId,
                leaseId,
                List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"u:after\"}}"))));

    assertEquals(0L, JournalEntry.count("updateId", updateId));

    long seq =
        ((Number)
                em.createNativeQuery("SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();
    assertEquals(0L, seq);
  }

  @Test
  void secondUpdateContinuesGlobalSequence() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(1, 1, 1L, "{\"state\":{\"urn\":\"u:1\"}}"),
            entry(2, 1, 2L, "{\"state\":{\"urn\":\"u:2\"}}")));

    // Release the first lock so we can take a fresh one for u2 — stack_locks is UNIQUE per stack.
    lockCanceller.gracefulCancel(leaseId, stackId, updateId);

    TestDb.ActiveUpdate u2 = db.seedActiveUpdate(stackId, StackUpdate.Kind.UPDATE);

    appender.append(
        stackId,
        u2.updateId(),
        u2.leaseId(),
        List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"u:2-1\"}}")));

    long offset2 =
        ((Number)
                em.createNativeQuery("SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
                    .setParameter(1, u2.updateId())
                    .getSingleResult())
            .longValue();
    // First update wrote sequence_id 1 and 2; journal_seq = 3 when update 2's first PATCH
    // arrives, so its offset is 3 and its first entry lands at sequence_id 4.
    assertEquals(3L, offset2);

    long seq =
        ((Number)
                em.createNativeQuery("SELECT sequence_id FROM journal_entries WHERE update_id = ?1")
                    .setParameter(1, u2.updateId())
                    .getSingleResult())
            .longValue();
    assertEquals(4L, seq);
  }

  // Assembled exports must pass Pulumi's own integrity rules even mid-race.
  private AssembledDeployment assemble(UUID targetStackId) {
    AssembledDeployment assembled = assembler.assemble(targetStackId);
    PulumiDeploymentAssert.assertIntegrityPasses(assembled.deployment());
    return assembled;
  }

  private static JsonNode entry(long wireSeq, int kind, Long operationId, String payloadJson) {
    try {
      JsonNode payload = MAPPER.readTree(payloadJson);
      com.fasterxml.jackson.databind.node.ObjectNode node =
          (com.fasterxml.jackson.databind.node.ObjectNode) payload;
      node.put("sequenceID", wireSeq);
      node.put("kind", kind);
      if (operationId != null) node.put("operationID", operationId);
      return node;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
