package io.statedb.app.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.lock.Lease;
import io.statedb.persistence.lock.StackLockAcquirer;
import io.statedb.persistence.lock.StackLockedException;
import io.statedb.persistence.stack.CompactionJob;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateCompleter;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackUpdateCompleterTest {

  private static final String ORG_NAME = "completer-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackUpdateCompleter completer;
  @Inject EntityManager em;

  @AfterEach
  void cleanup() {
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
            () ->
                em.createNativeQuery("UPDATE stack_state SET journal_seq = ?1 WHERE stack_id = ?2")
                    .setParameter(1, to)
                    .setParameter(2, stackId)
                    .executeUpdate());
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
  void failedUpdateStillBumpsVersion() {
    Fixture f = seed(StackUpdate.Kind.UPDATE, "complete-fail");

    completer.complete(f.leaseId(), f.stackId(), f.updateId(), StackUpdate.Status.FAILED);

    assertEquals(
        "failed", scalarString("SELECT status FROM stack_updates WHERE id = ?1", f.updateId()));
    assertEquals(1L, scalarLong("SELECT version FROM stacks WHERE id = ?1", f.stackId()));
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
}
