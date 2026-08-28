package app.statedb.app.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.journal.JournalAppender;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import app.statedb.persistence.update.StartedUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackLockAcquirerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "acquirer-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject JournalAppender journalAppender;
  @Inject EntityManager em;

  private UUID stackId;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Acquirer Test";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
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
  void freshAcquireReturnsLeaseAndAdvancesActiveUpdate() {
    UUID updateId = startUpdate("k1");
    Optional<Lease> lease =
        acquirer.acquire(stackId, updateId, "user:test", 1, Map.of("foo", "bar"));
    assertTrue(lease.isPresent());
    assertNotNull(lease.get().plaintextToken());
    assertEquals(7, lease.get().leaseId().version());

    Number lockCount =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(1L, lockCount.longValue());

    UUID active =
        (UUID)
            em.createNativeQuery("SELECT active_update_id FROM stacks WHERE id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(updateId, active);

    // Acquire body persisted to stack_updates.
    Object[] row =
        (Object[])
            em.createNativeQuery("SELECT journal_version, tags FROM stack_updates WHERE id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    assertEquals(1, ((Number) row[0]).intValue());
    assertNotNull(row[1]);
  }

  @Test
  void secondAcquireWhileFirstIsLiveReturnsEmpty() {
    UUID firstUpdateId = startUpdate("k-first");
    UUID secondUpdateId = startUpdate("k-second");
    assertTrue(acquirer.acquire(stackId, firstUpdateId, "user:a", null, null).isPresent());
    assertTrue(acquirer.acquire(stackId, secondUpdateId, "user:b", null, null).isEmpty());

    // The orphaned attempt is marked failed so it doesn't linger as in-progress forever.
    String status =
        (String)
            em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
                .setParameter(1, secondUpdateId)
                .getSingleResult();
    assertEquals("failed", status);

    // The lock-holder's row stays in-progress.
    String holderStatus =
        (String)
            em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
                .setParameter(1, firstUpdateId)
                .getSingleResult();
    assertEquals("in-progress", holderStatus);
  }

  @Test
  void idempotentRetryAgainstAlreadyFailedAttemptIsNoOp() {
    UUID firstUpdateId = startUpdate("k-holder");
    UUID lostUpdateId = startUpdate("k-lost");
    acquirer.acquire(stackId, firstUpdateId, "user:a", null, null).orElseThrow();
    assertTrue(acquirer.acquire(stackId, lostUpdateId, "user:b", null, null).isEmpty());
    // A second acquire against the already-failed row would 500 if the markFailed UPDATE were
    // unguarded — the terminal-status trigger rejects failed → failed transitions.
    assertTrue(acquirer.acquire(stackId, lostUpdateId, "user:b", null, null).isEmpty());
  }

  @Test
  void expiredLockIsStolenByFreshAcquire() {
    UUID firstUpdateId = startUpdate("k-orig");
    Lease first = acquirer.acquire(stackId, firstUpdateId, "user:a", null, null).orElseThrow();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackLock lock = StackLock.find("stackId", stackId).<StackLock>singleResult();
              lock.expiresAt = Instant.now().minusSeconds(1);
            });

    UUID secondUpdateId = startUpdate("k-stolen");
    Lease second = acquirer.acquire(stackId, secondUpdateId, "user:b", null, null).orElseThrow();
    assertNotEquals(first.plaintextToken(), second.plaintextToken());

    UUID dbUpdate =
        (UUID)
            em.createNativeQuery("SELECT update_id FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(secondUpdateId, dbUpdate);
  }

  @Test
  void oldLeaseCannotAppendAfterExpiredLockIsStolen() throws Exception {
    UUID firstUpdateId = startUpdate("k-old-append");
    Lease first = acquirer.acquire(stackId, firstUpdateId, "user:a", null, null).orElseThrow();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackLock lock = StackLock.find("stackId", stackId).<StackLock>singleResult();
              lock.expiresAt = Instant.now().minusSeconds(1);
            });

    UUID secondUpdateId = startUpdate("k-new-owner");
    assertTrue(acquirer.acquire(stackId, secondUpdateId, "user:b", null, null).isPresent());

    assertThrows(
        LeaseExpiredException.class,
        () ->
            journalAppender.append(
                stackId,
                firstUpdateId,
                first.leaseId(),
                List.of(entry(1, 1, 1L, "{\"state\":{\"urn\":\"urn:stale\"}}"))));

    assertEquals(0L, JournalEntry.count("updateId", firstUpdateId));
  }

  @Test
  void deletingStackCascadesLockRow() {
    UUID updateId = startUpdate("k-cascade");
    acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // Break the deferred FK to stack_updates first by clearing active_update_id.
              em.createNativeQuery("UPDATE stacks SET active_update_id = NULL WHERE id = ?1")
                  .setParameter(1, stackId)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM stacks WHERE id = ?1")
                  .setParameter(1, stackId)
                  .executeUpdate();
            });

    Number n =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(0L, n.longValue());
    Object stillThere =
        em
            .createNativeQuery("SELECT id FROM stacks WHERE id = ?1")
            .setParameter(1, stackId)
            .getResultList()
            .stream()
            .findFirst()
            .orElse(null);
    assertNull(stillThere);
  }

  private UUID startUpdate(String key) {
    StartUpdateRequest req =
        new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "desc", "yaml", "v3.234.0", "msg");
    StartedUpdate s = starter.start(stackId, StackUpdate.Kind.PREVIEW, null, null, key, req);
    return s.updateId();
  }

  private static JsonNode entry(long wireSeq, int kind, Long operationId, String payloadJson)
      throws Exception {
    JsonNode payload = MAPPER.readTree(payloadJson);
    var node =
        MAPPER.createObjectNode().put("sequenceID", wireSeq).put("kind", kind).put("version", 1);
    if (operationId != null) node.put("operationID", operationId);
    if (payload.has("state")) node.set("state", payload.required("state"));
    return node;
  }
}
