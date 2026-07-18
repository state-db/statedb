package io.statedb.app.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.RequestValidationException;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.journal.JournalAppender;
import io.statedb.persistence.journal.JournalSequenceConflictException;
import io.statedb.persistence.lock.Lease;
import io.statedb.persistence.lock.LeaseExpiredException;
import io.statedb.persistence.lock.StackLockAcquirer;
import io.statedb.persistence.lock.StackLockCanceller;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import io.statedb.persistence.update.StartedUpdate;
import io.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class JournalAppenderTest {

  private static final String ORG_NAME = "journal-org";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer lockAcquirer;
  @Inject StackLockCanceller lockCanceller;
  @Inject JournalAppender appender;
  @Inject StackAssembler assembler;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Journal Test";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
    StartedUpdate u =
        starter.start(
            stackId,
            StackUpdate.Kind.UPDATE,
            null,
            null,
            UUID.randomUUID().toString(),
            new StartUpdateRequest(
                Map.of(), Map.of(), Map.of(), "desc", "yaml", "v3.242.0", "msg"));
    updateId = u.updateId();
    Lease lease = lockAcquirer.acquire(stackId, updateId, "user:test", 1, null).orElseThrow();
    leaseId = lease.leaseId();
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long offset =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
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
                          em.createNativeQuery(
                                  "SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                              .setParameter(1, stackId)
                              .getSingleResult())
                      .longValue();
              assertEquals(4L, stateSeq);

              // pending_operations: Begins persist, Success terminates op 1.
              long pending =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM pending_operations WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, pending);
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
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
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              @SuppressWarnings("unchecked")
              List<Number> wireSeqs =
                  em.createNativeQuery(
                          "SELECT wire_sequence_id FROM journal_entries WHERE update_id = ?1 "
                              + "ORDER BY sequence_id")
                      .setParameter(1, updateId)
                      .getResultList();
              assertEquals(
                  List.of(3L, 4L, 5L, 6L), wireSeqs.stream().map(Number::longValue).toList());

              long stateSeq =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                              .setParameter(1, stackId)
                              .getSingleResult())
                      .longValue();
              assertEquals(7L, stateSeq);
            });
  }

  @Test
  void duplicateIdenticalPatchIsNoOp() {
    appender.append(
        stackId, updateId, leaseId, List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\"}}")));
    appender.append(
        stackId, updateId, leaseId, List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:1\"}}")));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, count);

              long stateSeq =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
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
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(3L, count);

              long stateSeq =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
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
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, count);
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(0L, count);
            });
  }

  @Test
  void nonPositiveSequenceRejected() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, List.of(entry(0, 0, 1L, "{}"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"creating", "updating", "deleting", "reading", "importing"})
  void beginPersistsAllPendingOperationTypes(String opType) {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(
                1,
                0,
                42L,
                "{\"operation\":{\"resource\":{\"urn\":\"urn:r\",\"type\":\"aws:s3:Bucket\"},\"type\":\""
                    + opType
                    + "\"}}")));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Object[] row =
                  (Object[])
                      em.createNativeQuery(
                              "SELECT resource_urn, resource_type, operation_type, begin_journal_seq "
                                  + "FROM pending_operations "
                                  + "WHERE update_id = ?1 AND operation_id = ?2")
                          .setParameter(1, updateId)
                          .setParameter(2, 42L)
                          .getSingleResult();
              assertEquals("urn:r", row[0]);
              assertEquals("aws:s3:Bucket", row[1]);
              assertEquals(opType, row[2]);
              assertEquals(1L, ((Number) row[3]).longValue());

              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM pending_operations WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, count);
            });
  }

  @ParameterizedTest
  @CsvSource({"1, Success", "2, Failure", "3, RefreshSuccess"})
  void terminalEntriesPrunePendingOpsTable(int terminalKind, String terminalLabel) {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(
                1,
                0,
                42L,
                "{\"operation\":{\"resource\":{\"urn\":\"urn:r\",\"type\":\"aws:s3:Bucket\"},\"type\":\"creating\"}}")));

    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(entry(2, terminalKind, 42L, terminalPayload(terminalKind))));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long pending =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM pending_operations WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(0L, pending);
            });
  }

  @Test
  void successBeforeBeginRaceLeavesStaleTableRow() {
    // Reproduces the wire-arrival race called out by JournalAppender.append's pending-ops loop:
    // batch 1 carries a terminal (Success) for op 42, batch 2 carries the late Begin for op 42 at
    // an earlier wire seq. The Success's DELETE no-ops (row didn't exist); the Begin's INSERT
    // succeeds, leaving a row in pending_operations. Replay-by-sequence_id still produces an empty
    // assembled pending_operations array, so pending_operations is not authoritative in this race.
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long pending =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM pending_operations WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, pending);
            });

    AssembledDeployment assembled = assemble(stackId);
    assertFalse(assembled.deployment().has("pending_operations"));
  }

  @Test
  void invalidOperationTypeSkipsPendingOpsButPersistsJournalRow() {
    // operation_type "bogus" is not in the CHECK set — pending_operations is silently skipped,
    // but the journal_entries row still lands.
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long je =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              long pending =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM pending_operations WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, je);
              assertEquals(0L, pending);
            });
  }

  @Test
  void payloadStoredWithoutPromotedFields() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            entry(1, 3, 9L, "{\"state\":{\"urn\":\"urn:s\"},\"isRefresh\":true,\"removeOld\":5}")));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              String payloadJson =
                  (String)
                      em.createNativeQuery(
                              "SELECT payload::text FROM journal_entries WHERE update_id = ?1")
                          .setParameter(1, updateId)
                          .getSingleResult();
              JsonNode payload;
              try {
                payload = MAPPER.readTree(payloadJson);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              // state remains in payload; promoted columns are removed.
              assertNotNull(payload.get("state"));
              assertNull(payload.get("isRefresh"));
              assertNull(payload.get("removeOld"));
              assertNull(payload.get("sequenceID"));
              assertNull(payload.get("kind"));

              Boolean isRefresh =
                  (Boolean)
                      em.createNativeQuery(
                              "SELECT is_refresh FROM journal_entries WHERE update_id = ?1")
                          .setParameter(1, updateId)
                          .getSingleResult();
              assertTrue(isRefresh);

              Number removeOld =
                  (Number)
                      em.createNativeQuery(
                              "SELECT remove_old FROM journal_entries WHERE update_id = ?1")
                          .setParameter(1, updateId)
                          .getSingleResult();
              assertEquals(5L, removeOld.longValue());
            });
  }

  @Test
  void payloadPrunesStackTraceButKeepsSourcePosition() {
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              String payloadJson =
                  (String)
                      em.createNativeQuery(
                              "SELECT payload::text FROM journal_entries WHERE update_id = ?1")
                          .setParameter(1, updateId)
                          .getSingleResult();
              JsonNode payload;
              try {
                payload = MAPPER.readTree(payloadJson);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              JsonNode state = payload.required("state");
              assertEquals(
                  "file:///work/Pulumi.yaml:12:3", state.required("sourcePosition").asText());
              assertNull(state.get("stackTrace"));
              assertNull(state.required("outputs").required("nested").get("stackTrace"));
            });
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

      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                long count =
                    ((Number)
                            em.createNativeQuery(
                                    "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                                .setParameter(1, updateId)
                                .getSingleResult())
                        .longValue();
                assertEquals(2L, count);
              });
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void appendAfterLockDeletedThrowsLeaseExpired() {
    // Simulate /complete (or a cancel) winning the race: lock row is gone before the appender's
    // tx starts. Without in-tx revalidation, append would advance journal_seq past
    // end_journal_seq; with it, requireLive throws and zero rows persist.
    lockCanceller.cancel(leaseId, stackId, updateId);

    assertThrows(
        LeaseExpiredException.class,
        () ->
            appender.append(
                stackId,
                updateId,
                leaseId,
                List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"u:after\"}}"))));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(0L, count);

              long seq =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT journal_seq FROM stack_state WHERE stack_id = ?1")
                              .setParameter(1, stackId)
                              .getSingleResult())
                      .longValue();
              assertEquals(0L, seq);
            });
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
    lockCanceller.cancel(leaseId, stackId, updateId);

    StartedUpdate u2 =
        starter.start(
            stackId,
            StackUpdate.Kind.UPDATE,
            null,
            null,
            UUID.randomUUID().toString(),
            new StartUpdateRequest(Map.of(), Map.of(), Map.of(), null, null, null, null));
    UUID lease2 =
        lockAcquirer.acquire(stackId, u2.updateId(), "user:test", 1, null).orElseThrow().leaseId();

    appender.append(
        stackId,
        u2.updateId(),
        lease2,
        List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"u:2-1\"}}")));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long offset2 =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
                              .setParameter(1, u2.updateId())
                              .getSingleResult())
                      .longValue();
              // First update wrote sequence_id 1 and 2; journal_seq = 3 when update 2's first PATCH
              // arrives, so its offset is 3 and its first entry lands at sequence_id 4.
              assertEquals(3L, offset2);

              long seq =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT sequence_id FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, u2.updateId())
                              .getSingleResult())
                      .longValue();
              assertEquals(4L, seq);
            });
  }

  // Assembled exports must pass Pulumi's own integrity rules even mid-race.
  private AssembledDeployment assemble(UUID targetStackId) {
    AssembledDeployment assembled = assembler.assemble(targetStackId);
    PulumiDeploymentAssert.assertIntegrityPasses(assembled.deployment());
    return assembled;
  }

  private static String terminalPayload(int terminalKind) {
    // Payload shape does not matter here: table pruning only needs kind + operationID.
    return terminalKind == 1 ? "{\"state\":{\"urn\":\"urn:r\"}}" : "{}";
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
