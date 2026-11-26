package app.statedb.app.events;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.events.EventBatchAppender;
import app.statedb.persistence.events.EventSequenceConflictException;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import app.statedb.persistence.update.StartedUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EventBatchAppenderTest {

  private static final String ORG_NAME = "events-org";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer lockAcquirer;
  @Inject EventBatchAppender appender;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;
  private UUID leaseId;

  @BeforeEach
  void seed() {
    stackId =
        read(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Events Test";
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
    leaseId = lockAcquirer.acquire(stackId, updateId, "test", 1, Map.of()).orElseThrow().leaseId();
  }

  @AfterEach
  void cleanup() {
    tx(
        () ->
            em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                .setParameter(1, ORG_NAME)
                .executeUpdate());
  }

  @TestTransaction
  @Test
  void singleBatchPersistsRowsWithPreservedSequence() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(
            event(0, "preludeEvent", "{\"config\":{}}"),
            event(1, "diagnosticEvent", "{\"severity\":\"info\",\"message\":\"hi\"}"),
            event(2, "summaryEvent", "{\"durationSeconds\":1}")));

    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        em.createNativeQuery(
                "SELECT sequence, kind, severity FROM update_events "
                    + "WHERE update_id = ?1 ORDER BY sequence")
            .setParameter(1, updateId)
            .getResultList();
    assertEquals(3, rows.size());
    assertEquals(0, ((Number) rows.get(0)[0]).intValue());
    assertEquals("prelude", rows.get(0)[1]);
    assertNull(rows.get(0)[2]);
    assertEquals("diagnostic", rows.get(1)[1]);
    assertEquals("info", rows.get(1)[2]);
    assertEquals("summary", rows.get(2)[1]);
  }

  @TestTransaction
  @Test
  void payloadStoredVerbatim() {
    appender.append(
        stackId,
        updateId,
        leaseId,
        List.of(event(7, "stdoutEvent", "{\"message\":\"hello\",\"color\":\"auto\"}")));

    String payloadJson =
        (String)
            em.createNativeQuery("SELECT payload::text FROM update_events WHERE update_id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    JsonNode payload;
    try {
      payload = MAPPER.readTree(payloadJson);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    // Full wire envelope is preserved, including sequence/timestamp keys.
    assertEquals(7, payload.get("sequence").asInt());
    assertTrue(payload.has("stdoutEvent"));
    assertEquals("hello", payload.get("stdoutEvent").get("message").asText());
  }

  @Test
  void duplicateSequenceReturnsConflict() {
    appender.append(
        stackId, updateId, leaseId, List.of(event(1, "stdoutEvent", "{\"message\":\"a\"}")));
    assertThrows(
        EventSequenceConflictException.class,
        () ->
            appender.append(
                stackId,
                updateId,
                leaseId,
                List.of(event(1, "stdoutEvent", "{\"message\":\"dup\"}"))));

    assertEquals(1L, eventCount(updateId));
  }

  @Test
  void missingKindEnvelopeRejected() {
    assertThrows(
        RequestValidationException.class,
        () ->
            appender.append(
                stackId, updateId, leaseId, List.of(MAPPER.createObjectNode().put("sequence", 1))));
  }

  @Test
  void ambiguousMultipleKindEnvelopesRejected() {
    assertThrows(
        RequestValidationException.class,
        () ->
            appender.append(
                stackId,
                updateId,
                leaseId,
                List.of(
                    event(1, "stdoutEvent", "{}", "diagnosticEvent", "{\"severity\":\"info\"}"))));
  }

  @Test
  void missingSequenceRejected() {
    JsonNode bad = MAPPER.createObjectNode().set("stdoutEvent", MAPPER.createObjectNode());
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, List.of(bad)));
  }

  @Test
  void nullEntryRejected() {
    // List.of() rejects nulls; use ArrayList so the null reaches the appender.
    java.util.ArrayList<JsonNode> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, withNull));
  }

  @Test
  void nonObjectEntryRejected() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, List.of(MAPPER.createArrayNode())));
  }

  @Test
  void negativeSequenceRejected() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, leaseId, List.of(event(-1, "stdoutEvent", "{}"))));
  }

  @Test
  void crossStackUpdateIdRejectedByCompositeFk() {
    // Smuggle this update's id under a *different* stack — Postgres composite FK
    // (update_id, stack_id) → stack_updates(id, stack_id) must reject.
    UUID otherStackId =
        read(
            () -> {
              Organization org2 = new Organization();
              org2.name = ORG_NAME + "-other";
              org2.displayName = "other";
              org2.persistAndFlush();
              StackRef ref =
                  stackCreator.create(org2.id, ORG_NAME + "-other", "p2", "dev", Map.of());
              return ref.stackId();
            });

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                appender.append(
                    otherStackId, updateId, leaseId, List.of(event(1, "stdoutEvent", "{}"))));
    // Pin the Postgres SQLSTATE — 23503 = foreign_key_violation. Asserting the SQLSTATE (rather
    // than just any ConstraintViolationException) verifies we're catching the composite FK
    // rejection specifically, not some other accidental constraint violation.
    String sqlState = null;
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException cve && cve.getSQLException() != null) {
        sqlState = cve.getSQLException().getSQLState();
        break;
      }
    }
    assertEquals("23503", sqlState, "expected FK violation (23503), got " + thrown);

    assertNoEventsAndRemoveForeignOrganization();
  }

  private static JsonNode event(int sequence, String kindKey, String innerJson) {
    try {
      var node = MAPPER.createObjectNode();
      node.put("sequence", sequence);
      node.set(kindKey, MAPPER.readTree(innerJson));
      return node;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static JsonNode event(
      int sequence, String kindKey1, String inner1, String kindKey2, String inner2) {
    try {
      var node = MAPPER.createObjectNode();
      node.put("sequence", sequence);
      node.set(kindKey1, MAPPER.readTree(inner1));
      node.set(kindKey2, MAPPER.readTree(inner2));
      return node;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private long eventCount(UUID updateId) {
    return read(
        () ->
            ((Number)
                    em.createNativeQuery("SELECT count(*) FROM update_events WHERE update_id = ?1")
                        .setParameter(1, updateId)
                        .getSingleResult())
                .longValue());
  }

  private void assertNoEventsAndRemoveForeignOrganization() {
    tx(
        () -> {
          long count =
              ((Number)
                      em.createNativeQuery(
                              "SELECT count(*) FROM update_events WHERE update_id = ?1")
                          .setParameter(1, updateId)
                          .getSingleResult())
                  .longValue();
          assertEquals(0L, count);
          em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
              .setParameter(1, ORG_NAME + "-other")
              .executeUpdate();
        });
  }
}
