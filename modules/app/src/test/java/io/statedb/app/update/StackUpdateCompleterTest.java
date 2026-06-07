package io.statedb.app.update;

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
import io.statedb.persistence.events.EventBatchAppender;
import io.statedb.persistence.events.UpdateEvent;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.journal.JournalAppender;
import io.statedb.persistence.journal.JournalEntry;
import io.statedb.persistence.journal.PendingOperation;
import io.statedb.persistence.lock.Lease;
import io.statedb.persistence.lock.StackLockAcquirer;
import io.statedb.persistence.lock.StackLockedException;
import io.statedb.persistence.stack.CompactionJob;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateCompleter;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackUpdateCompleterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "completer-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackUpdateCompleter completer;
  @Inject JournalAppender journalAppender;
  @Inject EventBatchAppender eventBatchAppender;
  @Inject StackAssembler assembler;
  @Inject EntityManager em;

  @AfterEach
  void cleanup() {
    cleanupOrg();
  }

  private void cleanupOrg() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());
  }

  private record Fixture(UUID stackId, UUID updateId, UUID leaseId) {}

  private Fixture seed(StackUpdate.Kind kind, String key) {
    UUID stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Completer Test";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
    UUID updateId =
        starter
            .start(
                stackId,
                kind,
                null,
                null,
                key,
                new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    return new Fixture(stackId, updateId, lease.leaseId());
  }

  private Fixture startOnStack(UUID stackId, String key) {
    UUID updateId =
        starter
            .start(
                stackId,
                StackUpdate.Kind.UPDATE,
                null,
                null,
                key,
                new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    return new Fixture(stackId, updateId, lease.leaseId());
  }

  private void bumpJournalSeq(UUID stackId, long to) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackState state = StackState.find("stackId", stackId).<StackState>singleResult();
              state.journalSeq = to;
            });
  }

  @Test
  void succeededUpdateReleasesLockBumpsVersionAndStampsEndSeq() {
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-ok");
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
    Fixture first = seed(StackUpdate.Kind.UPDATE, "complete-compact-1");

    completer.complete(
        first.leaseId(), first.stackId(), first.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(
        1L,
        CompactionJob.count(
            "stackId = ?1 and status = ?2", first.stackId(), CompactionJob.Status.PENDING));

    Fixture second = startOnStack(first.stackId(), "complete-compact-2");

    completer.complete(
        second.leaseId(), second.stackId(), second.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(
        1L,
        CompactionJob.count(
            "stackId = ?1 and status = ?2", first.stackId(), CompactionJob.Status.PENDING));
  }

  @Test
  void succeededPreviewLeavesStackVersionUnchanged() {
    Fixture f = seed(StackUpdate.Kind.PREVIEW, "complete-prev");
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
    Fixture f = seed(StackUpdate.Kind.PREVIEW, "complete-preview-events");

    eventBatchAppender.append(
        f.stackId(),
        f.updateId(),
        List.of(event(1, "diagnosticEvent", "{\"severity\":\"info\",\"message\":\"planning\"}")));

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

    assertEquals(1L, UpdateEvent.count("updateId", f.updateId()));
    assertEquals(0L, JournalEntry.count("updateId", f.updateId()));
    assertEquals(0, stack(f.stackId()).version);
    assertEquals(0, assembler.assemble(f.stackId()).deployment().required("resources").size());
  }

  @Test
  void mutatingKindsAdvanceVersionAndStampTerminalBoundary() {
    for (StackUpdate.Kind kind :
        List.of(
            StackUpdate.Kind.UPDATE,
            StackUpdate.Kind.REFRESH,
            StackUpdate.Kind.DESTROY,
            StackUpdate.Kind.IMPORT)) {
      try {
        Fixture f = seed(kind, "complete-kind-" + kind.name().toLowerCase());
        bumpJournalSeq(f.stackId(), 13L);

        completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED);

        StackUpdate update = StackUpdate.findById(f.updateId());
        assertEquals(StackUpdate.Status.SUCCEEDED, update.status);
        assertEquals(13L, update.endJournalSeq);
        assertEquals(1, stack(f.stackId()).version);
        assertEquals(0L, io.statedb.persistence.lock.StackLock.count("stackId", f.stackId()));
        assertNull(stack(f.stackId()).activeUpdateId);
      } finally {
        cleanupOrg();
      }
    }
  }

  @Test
  void failedUpdateStillBumpsVersion() {
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-fail");

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.FAILED);

    assertEquals(
        "failed", scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(1L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
  }

  @Test
  void failedUpdateWithNoSuccessfulResourcesLeavesExportUnchanged() throws Exception {
    Fixture succeeded = seed(StackUpdate.Kind.UPDATE, "complete-fail-base");
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

    Fixture failed = startOnStack(succeeded.stackId(), "complete-fail-terminal");
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

    StackUpdate failedUpdate = StackUpdate.findById(failed.updateId());
    assertEquals(StackUpdate.Status.FAILED, failedUpdate.status);
    assertEquals(0L, io.statedb.persistence.lock.StackLock.count("stackId", failed.stackId()));
    assertEquals(0L, PendingOperation.count("updateId", failed.updateId()));
    assertEquals(1, assembler.assemble(failed.stackId()).deployment().required("resources").size());
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
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-fail-partial-success");
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

    StackUpdate failedUpdate = StackUpdate.findById(f.updateId());
    assertEquals(StackUpdate.Status.FAILED, failedUpdate.status);
    assertEquals(0L, io.statedb.persistence.lock.StackLock.count("stackId", f.stackId()));
    assertEquals(0L, PendingOperation.count("updateId", f.updateId()));

    JsonNode resources = assembler.assemble(f.stackId()).deployment().required("resources");
    assertEquals(1, resources.size());
    assertEquals(urn("partial"), resources.get(0).required("urn").asText());
  }

  @Test
  void completeWaitsForStackStateLockBeforeStampingBoundary() throws Exception {
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-waits");
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
                            StackState state =
                                StackState.find("stackId", f.stackId())
                                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                                    .<StackState>singleResult();
                            state.journalSeq = 21;
                            locked.countDown();
                            await(release);
                          }));

      assertTrue(locked.await(5, TimeUnit.SECONDS));
      Future<?> completing =
          pool.submit(
              () ->
                  completer.complete(
                      f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.SUCCEEDED));

      Thread.sleep(200);
      assertFalse(completing.isDone(), "complete must wait for the stack_state row lock");

      release.countDown();
      holder.get(5, TimeUnit.SECONDS);
      completing.get(5, TimeUnit.SECONDS);

      StackUpdate update = StackUpdate.findById(f.updateId());
      assertEquals(21L, update.endJournalSeq);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void wrongLeaseIdThrowsAndLeavesLockIntact() {
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-wrong");
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
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-atomic");
    bumpJournalSeq(f.stackId(), 99L);

    // completer.complete is @Transactional(REQUIRED) so it joins this outer tx; throwing here
    // marks the outer tx for rollback and the lock DELETE / status flip / version bump all unwind.
    assertThrows(
        RuntimeException.class,
        () ->
            QuarkusTransaction.requiringNew()
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
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-terminal");
    // Simulate a concurrent cancel: status flipped, active_update_id cleared, lock still present
    // (in the real cancel path the lock is gone too — here we test the "lock + terminal status"
    // window, which is what makes this branch worth defending).
    QuarkusTransaction.requiringNew()
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

  private UUID scalarUuid(String sql, Object param) {
    return (UUID) em.createNativeQuery(sql).setParameter(1, param).getSingleResult();
  }

  private Long scalarLongOrNull(String sql, Object param) {
    Object o = em.createNativeQuery(sql).setParameter(1, param).getSingleResult();
    if (o == null) return null;
    return ((Number) o).longValue();
  }

  private io.statedb.persistence.stack.Stack stack(UUID stackId) {
    return io.statedb.persistence.stack.Stack.findById(stackId);
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
