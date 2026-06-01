package io.statedb.app.journal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.testsupport.RepoRoot;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
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
 * <p>The recorder ran with {@code --parallel=1} (see {@code tools/pulumi/recorder/CLAUDE.md}) so
 * these fixtures arrive with contiguous, in-order wire IDs — a useful sanity check on the
 * non-parallel path. Out-of-order parallel arrival is covered separately in {@link
 * JournalAppenderTest}.
 */
@QuarkusTest
class RecordedJournalFixtureReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXTURES_ROOT = "tools/pulumi/recorder/fixtures/by-command";
  private static final String RAW_TOKEN = "fixture-replay-tok";

  @Inject EntityManager em;
  @Inject StackCreator stackCreator;
  @Inject StackAssembler assembler;

  static Stream<Arguments> commands() {
    return Stream.of(
        Arguments.of("03-up", "replay-up-org", "p-up", "s-up"),
        Arguments.of("05-refresh", "replay-rfs-org", "p-rfs", "s-rfs"),
        Arguments.of("08-destroy", "replay-dst-org", "p-dst", "s-dst"));
  }

  private String org;

  @AfterEach
  void cleanup() {
    if (org == null) return;
    String orgName = org;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, orgName)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, orgName)
                  .executeUpdate();
            });
  }

  @ParameterizedTest(name = "replay {0}")
  @MethodSource("commands")
  void replayRecordedJournalPatches(String command, String orgName, String project, String stack)
      throws Exception {
    this.org = orgName;
    seedIdentityAndStack(orgName, project, stack);
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

    long expectedRows = expectedRowCount;
    long expectedMaxWire = maxWireSeq;
    UUID upd = UUID.fromString(updateId);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long actualRows =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, upd)
                              .getSingleResult())
                      .longValue();
              assertEquals(expectedRows, actualRows, command + ": journal row count mismatch");

              long maxServerSeq =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT MAX(sequence_id) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, upd)
                              .getSingleResult())
                      .longValue();
              long offset =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
                              .setParameter(1, upd)
                              .getSingleResult())
                      .longValue();
              // server.sequence_id = offset + wire.sequenceID per the appender's contract.
              assertEquals(
                  offset + expectedMaxWire,
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
              assertEquals(expectedRows, distinctWire);

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
                  expectedRows,
                  sinceCompaction,
                  command + ": journal_entries_since_compaction should equal rows appended");

              // At least one Begin (kind=0) means the recorded session had real resource ops, not
              // just SecretsManager setup — guards against silently replaying an empty stream.
              long beginCount =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries "
                                      + "WHERE update_id = ?1 AND kind = 0")
                              .setParameter(1, upd)
                              .getSingleResult())
                      .longValue();
              assertNotEquals(
                  0L, beginCount, command + ": expected at least one Begin entry in replay");
            });
  }

  @ParameterizedTest(name = "split replay {0}")
  @MethodSource("commands")
  void splitAndUnsplitRecordedJournalStreamProduceSameExport(
      String command, String orgName, String project, String stack) throws Exception {
    this.org = orgName;
    seedIdentityAndStack(orgName, project, stack + "-one");
    seedStack(orgName, project, stack + "-split");

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

    UUID unsplitStackId = stackId(orgName, project, stack + "-one");
    UUID splitStackId = stackId(orgName, project, stack + "-split");
    AssembledDeployment unsplit = assembler.assemble(unsplitStackId);
    AssembledDeployment split = assembler.assemble(splitStackId);
    assertEquals(
        comparableDeployment(unsplit.deployment()),
        comparableDeployment(split.deployment()),
        command + ": export mismatch");
  }

  private void seedIdentityAndStack(String orgName, String project, String stack) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization o = new Organization();
              o.name = orgName;
              o.displayName = orgName;
              o.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = orgName;
              user.email = orgName + "@example.com";
              user.displayName = orgName;
              user.persistAndFlush();

              OrgMember m = new OrgMember();
              m.orgId = o.id;
              m.userId = user.id;
              m.role = OrgMember.Role.owner;
              m.persistAndFlush();

              ApiToken t = new ApiToken();
              t.tokenHash = sha256Hex(RAW_TOKEN);
              t.userId = user.id;
              t.orgId = o.id;
              t.kind = ApiToken.Kind.service_account;
              t.description = "replay";
              t.persistAndFlush();

              stackCreator.create(o.id, orgName, project, stack, Map.of());
            });
  }

  private void seedStack(String orgName, String project, String stack) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = Organization.<Organization>find("name", orgName).firstResult();
              stackCreator.create(org.id, orgName, project, stack, Map.of());
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

  private UUID stackId(String orgName, String project, String stack) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Organization org = Organization.<Organization>find("name", orgName).firstResult();
              Project p = Project.find("orgId = ?1 and name = ?2", org.id, project).firstResult();
              io.statedb.persistence.stack.Stack s =
                  io.statedb.persistence.stack.Stack.<io.statedb.persistence.stack.Stack>find(
                          "projectId = ?1 and name = ?2", p.id, stack)
                      .firstResult();
              return s.id;
            });
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

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
