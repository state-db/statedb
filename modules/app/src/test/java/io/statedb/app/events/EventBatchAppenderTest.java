package io.statedb.app.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.RequestValidationException;
import io.statedb.persistence.events.EventBatchAppender;
import io.statedb.persistence.events.EventSequenceConflictException;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import io.statedb.persistence.update.StartedUpdate;
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
  @Inject EventBatchAppender appender;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
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
  void singleBatchPersistsRowsWithPreservedSequence() {
    appender.append(
        stackId,
        updateId,
        List.of(
            event(0, "preludeEvent", "{\"config\":{}}"),
            event(1, "diagnosticEvent", "{\"severity\":\"info\",\"message\":\"hi\"}"),
            event(2, "summaryEvent", "{\"durationSeconds\":1}")));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
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
            });
  }

  @Test
  void payloadStoredVerbatim() {
    appender.append(
        stackId,
        updateId,
        List.of(event(7, "stdoutEvent", "{\"message\":\"hello\",\"color\":\"auto\"}")));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              String payloadJson =
                  (String)
                      em.createNativeQuery(
                              "SELECT payload::text FROM update_events WHERE update_id = ?1")
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
            });
  }

  @Test
  void duplicateSequenceReturnsConflict() {
    appender.append(stackId, updateId, List.of(event(1, "stdoutEvent", "{\"message\":\"a\"}")));
    assertThrows(
        EventSequenceConflictException.class,
        () ->
            appender.append(
                stackId, updateId, List.of(event(1, "stdoutEvent", "{\"message\":\"dup\"}"))));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM update_events WHERE update_id = ?1")
                              .setParameter(1, updateId)
                              .getSingleResult())
                      .longValue();
              assertEquals(1L, count);
            });
  }

  @Test
  void missingKindEnvelopeRejected() {
    assertThrows(
        RequestValidationException.class,
        () ->
            appender.append(
                stackId, updateId, List.of(MAPPER.createObjectNode().put("sequence", 1))));
  }

  @Test
  void ambiguousMultipleKindEnvelopesRejected() {
    assertThrows(
        RequestValidationException.class,
        () ->
            appender.append(
                stackId,
                updateId,
                List.of(
                    event(1, "stdoutEvent", "{}", "diagnosticEvent", "{\"severity\":\"info\"}"))));
  }

  @Test
  void missingSequenceRejected() {
    JsonNode bad = MAPPER.createObjectNode().set("stdoutEvent", MAPPER.createObjectNode());
    assertThrows(
        RequestValidationException.class, () -> appender.append(stackId, updateId, List.of(bad)));
  }

  @Test
  void nullEntryRejected() {
    // List.of() rejects nulls; use ArrayList so the null reaches the appender.
    java.util.ArrayList<JsonNode> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThrows(
        RequestValidationException.class, () -> appender.append(stackId, updateId, withNull));
  }

  @Test
  void nonObjectEntryRejected() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, List.of(MAPPER.createArrayNode())));
  }

  @Test
  void negativeSequenceRejected() {
    assertThrows(
        RequestValidationException.class,
        () -> appender.append(stackId, updateId, List.of(event(-1, "stdoutEvent", "{}"))));
  }

  @Test
  void crossStackUpdateIdRejectedByCompositeFk() {
    // Smuggle this update's id under a *different* stack — Postgres composite FK
    // (update_id, stack_id) → stack_updates(id, stack_id) must reject.
    UUID otherStackId =
        QuarkusTransaction.requiringNew()
            .call(
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
            () -> appender.append(otherStackId, updateId, List.of(event(1, "stdoutEvent", "{}"))));
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

    QuarkusTransaction.requiringNew()
        .run(
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
}
