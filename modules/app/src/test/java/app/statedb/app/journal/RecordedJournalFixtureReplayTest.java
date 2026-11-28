package app.statedb.app.journal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.stack.AssembledDeployment;
import app.statedb.persistence.stack.StackAssembler;
import app.statedb.testsupport.RepoRoot;
import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end replay of every recorded {@code PATCH .../journalentries} body from the recorder
 * fixtures for {@code 03-up}, {@code 05-refresh}, and {@code 08-destroy}. For each command the test
 * provisions a fresh stack + update + lease through normal API calls, then sends the recorded
 * gzipped bodies in fixture order against our handler. We verify all rows landed and the wire
 * sequenceIDs round-trip into the right server {@code sequence_id} window.
 *
 * <p>The recorder ran with {@code --parallel=1} (see {@code tools/pulumi/recorder/AGENTS.md}) so
 * these fixtures arrive with contiguous, in-order wire IDs — a useful sanity check on the
 * non-parallel path. Out-of-order parallel arrival is covered separately in {@link
 * JournalAppenderTest}.
 */
@QuarkusTest
class RecordedJournalFixtureReplayTest extends DatabaseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXTURES_ROOT = "tools/pulumi/recorder/fixtures/by-command";
  private static final String RAW_TOKEN = "fixture-replay-tok";

  @Inject StackAssembler assembler;

  static Stream<Arguments> commands() {
    return Stream.of(
        Arguments.of("03-up", "replay-up-org", "p-up", "s-up"),
        Arguments.of("05-refresh", "replay-rfs-org", "p-rfs", "s-rfs"),
        Arguments.of("08-destroy", "replay-dst-org", "p-dst", "s-dst"));
  }

  @ParameterizedTest(name = "replay {0}")
  @MethodSource("commands")
  void replayRecordedJournalPatches(String command, String orgName, String project, String stack)
      throws Exception {
    db.seedOwnerWithStack(orgName, RAW_TOKEN, project, stack);
    String updateId = startUpdate(orgName, project, stack);
    String token = acquireLock(orgName, project, stack, updateId);

    List<Path> fixtures = journalFixtures(command);
    assertTrue(
        fixtures.size() >= 5,
        "expected several recorded journal PATCHes under " + command + ", got " + fixtures.size());

    long expectedRowCount = 0;
    long maxWireSeq = 0;
    for (Path fixture : fixtures) {
      JsonNode exchange = MAPPER.readTree(fixture.toFile());
      assertTrue(exchange.path("body_gzip").asBoolean(false), fixture + " is not gzipped");
      byte[] body = Base64.getDecoder().decode(exchange.required("body_b64").asText());

      given()
          .header("Authorization", "update-token " + token)
          .header("Content-Encoding", "gzip")
          .contentType("application/json")
          .body(body)
          .when()
          .patch(
              "/pulumi/api/stacks/"
                  + orgName
                  + "/"
                  + project
                  + "/"
                  + stack
                  + "/update/"
                  + updateId
                  + "/journalentries")
          .then()
          .statusCode(200);

      JsonNode entries = exchange.required("body").required("entries");
      expectedRowCount += entries.size();
      for (JsonNode entry : entries) {
        maxWireSeq = Math.max(maxWireSeq, entry.required("sequenceID").asLong());
      }
    }

    UUID upd = UUID.fromString(updateId);
    assertEquals(
        expectedRowCount,
        JournalEntry.count("updateId", upd),
        command + ": journal row count mismatch");

    long maxServerSeq =
        ((Number)
                em.createNativeQuery(
                        "SELECT MAX(sequence_id) FROM journal_entries WHERE update_id = ?1")
                    .setParameter(1, upd)
                    .getSingleResult())
            .longValue();
    long offset =
        ((Number)
                em.createNativeQuery("SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
                    .setParameter(1, upd)
                    .getSingleResult())
            .longValue();
    // server.sequence_id = offset + wire.sequenceID per the appender's contract.
    assertEquals(
        offset + maxWireSeq,
        maxServerSeq,
        command + ": max sequence_id should equal offset + max wire seq");

    // Every wire sequenceID 1..N from the recorded run must appear exactly once.
    long distinctWire =
        ((Number)
                em.createNativeQuery(
                        "SELECT count(DISTINCT wire_sequence_id) FROM journal_entries "
                            + "WHERE update_id = ?1")
                    .setParameter(1, upd)
                    .getSingleResult())
            .longValue();
    assertEquals(expectedRowCount, distinctWire);

    // journal_entries_since_compaction must equal the number of rows we just appended —
    // pins step 8 of the appender (the counter that compaction reads to decide when to
    // run). Done as a stack-level read to also catch the case where the counter wasn't
    // bumped on the right row.
    long sinceCompaction =
        ((Number)
                em.createNativeQuery(
                        "SELECT journal_entries_since_compaction FROM stack_state "
                            + "WHERE stack_id = (SELECT stack_id FROM stack_updates "
                            + " WHERE id = ?1)")
                    .setParameter(1, upd)
                    .getSingleResult())
            .longValue();
    assertEquals(
        expectedRowCount,
        sinceCompaction,
        command + ": journal_entries_since_compaction should equal rows appended");

    // At least one Begin (kind=0) means the recorded session had real resource ops, not
    // just SecretsManager setup — guards against silently replaying an empty stream.
    long beginCount = JournalEntry.count("updateId = ?1 and kind = ?2", upd, (short) 0);
    assertNotEquals(0L, beginCount, command + ": expected at least one Begin entry in replay");
  }

  @Test
  void recordedRefreshReplayHasTerminalRebuildAndCleanExportReferences() throws Exception {
    String org = "replay-refresh-integrity-org";
    String project = "p-refresh-integrity";
    String stack = "s-refresh-integrity";
    UUID stackId = db.seedOwnerWithStack(org, RAW_TOKEN, project, stack).stack().stackId();

    replayCommand(org, project, stack, "03-up");
    replayCommand(org, project, stack, "05-refresh");

    List<JsonNode> refreshEntries = recordedEntries("05-refresh");
    JsonNode last = refreshEntries.get(refreshEntries.size() - 1);
    assertEquals(
        7, last.required("kind").asInt(), "recorded refresh must end with RebuiltBaseState");
    assertTrue(
        refreshEntries.stream()
            .anyMatch(
                entry ->
                    entry.path("kind").asInt() == 3
                        && entry.path("removeOld").isNumber()
                        && entry.hasNonNull("state")),
        "recorded refresh should include RefreshSuccess(removeOld, state) replacement rows");

    JsonNode resources = assemble(stackId).deployment().required("resources");

    assertNoDanglingExportReferences(resources);
  }

  @ParameterizedTest(name = "split replay {0}")
  @MethodSource("commands")
  void splitAndUnsplitRecordedJournalStreamProduceSameExport(
      String command, String orgName, String project, String stack) throws Exception {
    UUID unsplitStackId =
        db.seedOwnerWithStack(orgName, RAW_TOKEN, project, stack + "-one").stack().stackId();
    UUID splitStackId = db.seedStack(orgName, project, stack + "-split").stackId();

    String unsplitUpdate = startUpdate(orgName, project, stack + "-one");
    String unsplitToken = acquireLock(orgName, project, stack + "-one", unsplitUpdate);
    String splitUpdate = startUpdate(orgName, project, stack + "-split");
    String splitToken = acquireLock(orgName, project, stack + "-split", splitUpdate);

    List<JsonNode> entries = recordedEntries(command);
    assertTrue(entries.size() > 1, command + ": need multiple entries to exercise split replay");

    postJournalBatch(orgName, project, stack + "-one", unsplitUpdate, unsplitToken, entries);
    for (int from = 0; from < entries.size(); from += 3) {
      postJournalBatch(
          orgName,
          project,
          stack + "-split",
          splitUpdate,
          splitToken,
          entries.subList(from, Math.min(from + 3, entries.size())));
    }

    AssembledDeployment unsplit = assemble(unsplitStackId);
    AssembledDeployment split = assemble(splitStackId);
    assertEquals(
        comparableDeployment(unsplit.deployment()),
        comparableDeployment(split.deployment()),
        command + ": export mismatch");
  }

  private String replayCommand(String orgName, String project, String stack, String command)
      throws Exception {
    String updateId = startUpdate(orgName, project, stack);
    String token = acquireLock(orgName, project, stack, updateId);
    for (Path fixture : journalFixtures(command)) {
      JsonNode exchange = MAPPER.readTree(fixture.toFile());
      assertTrue(exchange.path("body_gzip").asBoolean(false), fixture + " is not gzipped");
      byte[] body = Base64.getDecoder().decode(exchange.required("body_b64").asText());

      given()
          .header("Authorization", "update-token " + token)
          .header("Content-Encoding", "gzip")
          .contentType("application/json")
          .body(body)
          .when()
          .patch(
              "/pulumi/api/stacks/"
                  + orgName
                  + "/"
                  + project
                  + "/"
                  + stack
                  + "/update/"
                  + updateId
                  + "/journalentries")
          .then()
          .statusCode(200);
    }
    completeUpdate(orgName, project, stack, updateId, token);
    return updateId;
  }

  private static void completeUpdate(
      String orgName, String project, String stack, String updateId, String token) {
    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of("status", "succeeded"))
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
                + "/complete")
        .then()
        .statusCode(200);
  }

  private static void assertNoDanglingExportReferences(JsonNode resources) {
    Set<String> seen = new HashSet<>();
    for (JsonNode resource : resources) {
      assertUrnArrayReferencesSeen(resource, "dependencies", seen);
      assertUrnArrayReferencesSeen(resource, "replaceWith", seen);
      assertPropertyDependenciesSeen(resource, seen);

      String urn = resource.required("urn").asText();
      String parent = resource.path("parent").asText("");
      if (!parent.isEmpty()) assertTrue(seen.contains(parent), "parent not seen before " + urn);
      String deletedWith = resource.path("deletedWith").asText("");
      if (!deletedWith.isEmpty()) {
        assertTrue(seen.contains(deletedWith), "deletedWith not seen before " + urn);
      }
      seen.add(urn);
    }
  }

  private static void assertUrnArrayReferencesSeen(
      JsonNode resource, String field, Set<String> seen) {
    JsonNode values = resource.get(field);
    if (values == null || !values.isArray()) return;
    for (JsonNode value : values) {
      String urn = value.asText("");
      if (!urn.isEmpty()) {
        assertTrue(
            seen.contains(urn),
            field + " reference not seen before " + resource.required("urn").asText() + ": " + urn);
      }
    }
  }

  private static void assertPropertyDependenciesSeen(JsonNode resource, Set<String> seen) {
    JsonNode propertyDependencies = resource.get("propertyDependencies");
    if (propertyDependencies == null || !propertyDependencies.isObject()) return;
    propertyDependencies
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode values = entry.getValue();
              if (values == null || !values.isArray()) return;
              for (JsonNode value : values) {
                String urn = value.asText("");
                if (!urn.isEmpty()) {
                  assertTrue(
                      seen.contains(urn),
                      "propertyDependencies."
                          + entry.getKey()
                          + " reference not seen before "
                          + resource.required("urn").asText()
                          + ": "
                          + urn);
                }
              }
            });
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

  private static List<Path> journalFixtures(String command) throws Exception {
    Path dir = RepoRoot.get().resolve(FIXTURES_ROOT).resolve(command);
    try (var stream = Files.list(dir)) {
      return stream
          .filter(p -> p.getFileName().toString().endsWith("_journalentries.json"))
          .sorted()
          .toList();
    }
  }

  private static List<JsonNode> recordedEntries(String command) throws Exception {
    try (Stream<Path> fixtures = journalFixtures(command).stream()) {
      return fixtures
          .flatMap(
              fixture -> {
                try {
                  JsonNode entries =
                      MAPPER.readTree(fixture.toFile()).required("body").required("entries");
                  return IntStream.range(0, entries.size()).mapToObj(entries::get);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              })
          .toList();
    }
  }

  private static void postJournalBatch(
      String orgName,
      String project,
      String stack,
      String updateId,
      String token,
      List<JsonNode> entries)
      throws Exception {
    byte[] body = gzip(MAPPER.writeValueAsBytes(Map.of("entries", entries)));
    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(body)
        .when()
        .patch(
            "/pulumi/api/stacks/"
                + orgName
                + "/"
                + project
                + "/"
                + stack
                + "/update/"
                + updateId
                + "/journalentries")
        .then()
        .statusCode(200);
  }

  // Deployments assembled from real recorded CLI journals must pass Pulumi's own integrity rules.
  private AssembledDeployment assemble(UUID stackId) {
    AssembledDeployment assembled = assembler.assemble(stackId);
    PulumiDeploymentAssert.assertIntegrityPasses(assembled.deployment());
    return assembled;
  }

  private static JsonNode comparableDeployment(JsonNode deployment) {
    ObjectNode copy = deployment.deepCopy();
    JsonNode manifest = copy.get("manifest");
    if (manifest instanceof ObjectNode manifestObject) {
      manifestObject.remove("time");
    }
    return copy;
  }

  private static byte[] gzip(byte[] data) {
    try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(baos)) {
      gz.write(data);
      gz.finish();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
