package app.statedb.app.events;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.testsupport.RepoRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end replay of every recorded {@code POST .../events/batch} body from the recorder
 * fixtures. For each command we provision a fresh stack + update + lease through normal API calls,
 * then send the recorded gzipped bodies through the live HTTP handler. We verify the row count and
 * max wire sequence match the recorded payload.
 */
@QuarkusTest
class RecordedEventsBatchReplayTest extends DatabaseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXTURES_ROOT = "tools/pulumi/recorder/fixtures/by-command";
  private static final String RAW_TOKEN = "events-replay-tok";

  static Stream<Arguments> commands() {
    return Stream.of(
        Arguments.of("03-up", "evt-up-org", "p-up", "s-up"),
        Arguments.of("04-preview", "evt-prv-org", "p-prv", "s-prv"),
        Arguments.of("05-refresh", "evt-rfs-org", "p-rfs", "s-rfs"),
        Arguments.of("08-destroy", "evt-dst-org", "p-dst", "s-dst"));
  }

  // Filenames embed the upstream update slug, e.g. ".../update_update-0002_events_batch.json".
  // A single recorded command may include multiple updates (e.g. an implicit preview before the
  // user-facing up). We replay each slug into its own freshly-acquired update so that recorded
  // sequences — which Pulumi resets per update — don't collide on our UNIQUE(update_id, sequence)
  // constraint. Each iteration releases the lock directly so the next acquire succeeds without
  // a real completion endpoint to release it for us.
  private static final Pattern UPDATE_SLUG =
      Pattern.compile("_update_([^_]+)_events_batch\\.json$");

  @ParameterizedTest(name = "replay {0}")
  @MethodSource("commands")
  void replayRecordedEventBatches(String command, String orgName, String project, String stack)
      throws Exception {
    db.seedOwnerWithStack(orgName, RAW_TOKEN, project, stack);

    Map<String, List<Path>> bySlug = eventFixturesBySlug(command);
    assertTrue(!bySlug.isEmpty(), "expected at least one recorded events/batch under " + command);

    for (Map.Entry<String, List<Path>> group : bySlug.entrySet()) {
      String slug = group.getKey();
      List<Path> fixtures = group.getValue();
      // Clear any prior lease so successive acquires in the same test don't 409. A real
      // completion endpoint would do this for us; until then, hand-release.
      releaseLock(orgName, project, stack);
      String updateId = startUpdate(orgName, project, stack);
      String token = acquireLock(orgName, project, stack, updateId);

      long expectedRowCount = 0;
      int maxWireSeq = Integer.MIN_VALUE;
      for (Path fixture : fixtures) {
        JsonNode exchange = MAPPER.readTree(fixture.toFile());
        assertTrue(exchange.path("body_gzip").asBoolean(false), fixture + " is not gzipped");
        byte[] body = Base64.getDecoder().decode(exchange.required("body_b64").asText());

        int expectedStatus = exchange.required("status_code").asInt();
        given()
            .header("Authorization", "update-token " + token)
            .header("Content-Encoding", "gzip")
            .contentType("application/json")
            .body(body)
            .when()
            .post(
                "/pulumi/api/stacks/"
                    + orgName
                    + "/"
                    + project
                    + "/"
                    + stack
                    + "/update/"
                    + updateId
                    + "/events/batch")
            .then()
            .statusCode(expectedStatus);

        JsonNode events = exchange.required("body").required("events");
        expectedRowCount += events.size();
        for (JsonNode ev : events) {
          int s = ev.required("sequence").asInt();
          if (s > maxWireSeq) maxWireSeq = s;
        }
      }

      UUID upd = UUID.fromString(updateId);
      assertEquals(
          expectedRowCount,
          UpdateEvent.count("updateId", upd),
          command + "/" + slug + ": event row count mismatch");

      int maxSeq =
          ((Number)
                  em.createNativeQuery(
                          "SELECT MAX(sequence) FROM update_events WHERE update_id = ?1")
                      .setParameter(1, upd)
                      .getSingleResult())
              .intValue();
      assertEquals(maxWireSeq, maxSeq, command + "/" + slug + ": max sequence mismatch");
    }
  }

  @Test
  void emptyEventsArrayReturns400() {
    String orgName = "evt-empty-org";
    String project = "p-empty";
    String stack = "s-empty";
    db.seedOwnerWithStack(orgName, RAW_TOKEN, project, stack);
    String updateId = startUpdate(orgName, project, stack);
    String token = acquireLock(orgName, project, stack, updateId);

    // Pulumi Cloud returns 400 "No events provided" for empty batches; mirror it.
    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of("events", List.of()))
        .when()
        .post(
            "/pulumi/api/stacks/"
                + orgName
                + "/"
                + project
                + "/"
                + stack
                + "/update/"
                + updateId
                + "/events/batch")
        .then()
        .statusCode(400);
  }

  @Test
  void malformedGzipBodyReturns400() {
    String orgName = "evt-bad-gzip-org";
    String project = "p-bad";
    String stack = "s-bad";
    db.seedOwnerWithStack(orgName, RAW_TOKEN, project, stack);
    String updateId = startUpdate(orgName, project, stack);
    String token = acquireLock(orgName, project, stack, updateId);

    // Garbage bytes claimed to be gzip — Vert.x' decompressor drops the body to null. Without
    // the handler's explicit null check this would 204 as a no-op and the CLI would lose events.
    // Same payload shape as JournalEntriesContractTest's malformedGzipReturns400NotSilent200.
    byte[] notGzip = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(notGzip)
        .when()
        .post(
            "/pulumi/api/stacks/"
                + orgName
                + "/"
                + project
                + "/"
                + stack
                + "/update/"
                + updateId
                + "/events/batch")
        .then()
        .statusCode(400);

    assertEquals(
        0L,
        UpdateEvent.count("updateId", UUID.fromString(updateId)),
        "no rows should land for a malformed gzip body");
  }

  @Test
  void duplicateEventSequenceReturnsPulumiConflict() {
    String orgName = "evt-conflict-org";
    String project = "p-conflict";
    String stack = "s-conflict";
    db.seedOwnerWithStack(orgName, RAW_TOKEN, project, stack);
    String updateId = startUpdate(orgName, project, stack);
    String token = acquireLock(orgName, project, stack, updateId);
    String path =
        "/pulumi/api/stacks/"
            + orgName
            + "/"
            + project
            + "/"
            + stack
            + "/update/"
            + updateId
            + "/events/batch";

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(
            Map.of(
                "events",
                List.of(
                    Map.of(
                        "sequence",
                        1,
                        "stdoutEvent",
                        Map.of("message", "first", "color", "auto")))))
        .when()
        .post(path)
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(
            Map.of(
                "events",
                List.of(
                    Map.of(
                        "sequence",
                        1,
                        "stdoutEvent",
                        Map.of("message", "different", "color", "auto")))))
        .when()
        .post(path)
        .then()
        .statusCode(409)
        .contentType(JSON)
        .body("code", equalTo(409))
        .body("message", containsString("duplicate event sequence for update"));
  }

  private String startUpdate(String orgName, String project, String stack) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", project, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + orgName + "/" + project + "/" + stack + "/update")
        .then()
        .statusCode(200)
        .extract()
        .path("updateID");
  }

  private void releaseLock(String orgName, String project, String stack) {
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "DELETE FROM stack_locks WHERE stack_id = "
                            + "(SELECT s.id FROM stacks s "
                            + "  JOIN projects p ON p.id = s.project_id "
                            + "  JOIN organizations o ON o.id = p.org_id "
                            + " WHERE o.name = ?1 AND p.name = ?2 AND s.name = ?3)")
                    .setParameter(1, orgName)
                    .setParameter(2, project)
                    .setParameter(3, stack)
                    .executeUpdate());
  }

  private String acquireLock(String orgName, String project, String stack, String updateId) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + orgName + "/" + project + "/" + stack + "/update/" + updateId)
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }

  private static Map<String, List<Path>> eventFixturesBySlug(String command) throws Exception {
    Path dir = RepoRoot.get().resolve(FIXTURES_ROOT).resolve(command);
    Map<String, List<Path>> bySlug = new LinkedHashMap<>();
    try (var stream = Files.list(dir)) {
      stream
          .filter(p -> p.getFileName().toString().endsWith("_events_batch.json"))
          .sorted()
          .forEach(
              p -> {
                Matcher m = UPDATE_SLUG.matcher(p.getFileName().toString());
                if (m.find()) {
                  bySlug.computeIfAbsent(m.group(1), k -> new java.util.ArrayList<>()).add(p);
                }
              });
    }
    return bySlug;
  }
}
