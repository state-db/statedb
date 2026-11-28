package app.statedb.app.stack;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.Snapshot;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code DELETE /api/stacks/{org}/{project}/{stack}} against the recorded fixture under {@code
 * tools/pulumi/recorder/fixtures/by-command/08-stack-rm/}.
 */
@QuarkusTest
class StackDeleteContractTest extends DatabaseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "stack-delete-org";
  private static final String OTHER_ORG_NAME = "stack-delete-other-org";
  private static final String RAW_TOKEN = "stack-delete-token";
  private static final String PROJECT = "recorder-test";

  @BeforeEach
  void seed() {
    db.seedOwner(ORG_NAME, RAW_TOKEN);
    db.seedOrg(OTHER_ORG_NAME);
  }

  @Test
  void deletesEmptyStackAndReturnsPulumiShape() {
    StackRef ref = db.seedStack(ORG_NAME, PROJECT, "dev", fixtureTags());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/dev?force=false")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackRows(0L, ref);
  }

  @Test
  void nonEmptyStackReturns409WithoutForce() {
    StackRef ref = db.seedStack(ORG_NAME, PROJECT, "busy", fixtureTags());
    setBaseSnapshot(
        ref.stackId(),
        1L,
        "[{\"urn\":\"urn:pulumi:dev::recorder-test::pkg:index:Thing::thing\","
            + "\"type\":\"pkg:index:Thing\",\"custom\":true}]");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/busy?force=false")
        .then()
        .statusCode(409);

    assertStackRows(1L, ref);
  }

  @Test
  void deletesStackWithOnlyRootStackResourceAndJournalHistory() {
    StackRef ref = db.seedStack(ORG_NAME, PROJECT, "destroyed", fixtureTags());
    setBaseSnapshot(
        ref.stackId(),
        42L,
        "[{\"urn\":\"urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev\","
            + "\"type\":\"pulumi:pulumi:Stack\",\"custom\":false,\"outputs\":{}}]");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/destroyed?force=false")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackRows(0L, ref);
  }

  @Test
  void forceDeletesNonEmptyStack() {
    StackRef ref = db.seedStack(ORG_NAME, PROJECT, "force", fixtureTags());
    setBaseSnapshot(
        ref.stackId(),
        1L,
        "[{\"urn\":\"urn:pulumi:dev::recorder-test::pkg:index:Thing::thing\","
            + "\"type\":\"pkg:index:Thing\",\"custom\":true}]");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/force?force=true")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackRows(0L, ref);
  }

  @Test
  void rejectsDeleteWhileUpdateIsActive() {
    StackRef ref = db.seedStack(ORG_NAME, PROJECT, "active", fixtureTags());
    UUID updateId = db.seedActiveUpdate(ref.stackId(), StackUpdate.Kind.UPDATE).updateId();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/active?force=false")
        .then()
        .statusCode(409);

    assertStackRows(1L, ref);
    assertEquals(1L, StackUpdate.count("id", updateId));
    assertEquals(1L, StackLock.count("stackId", ref.stackId()));
  }

  @Test
  void forceDeletesStackWithActiveUpdate() {
    StackRef ref = db.seedStack(ORG_NAME, PROJECT, "active-force", fixtureTags());
    UUID updateId = db.seedActiveUpdate(ref.stackId(), StackUpdate.Kind.UPDATE).updateId();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/active-force?force=true")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackRows(0L, ref);
    assertEquals(0L, StackUpdate.count("id", updateId));
    assertEquals(0L, StackLock.count("stackId", ref.stackId()));
  }

  @Test
  void missingStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/missing?force=false")
        .then()
        .statusCode(404);
  }

  @Test
  void crossOrgCollapsesTo404() {
    db.seedStack(OTHER_ORG_NAME, "secret-proj", "secret");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .delete("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/secret-proj/secret?force=false")
        .then()
        .statusCode(404);
  }

  private void assertStackRows(long expected, StackRef ref) {
    assertEquals(expected, Workspace.count("id", ref.workspaceId()));
    assertEquals(expected, Stack.count("id", ref.stackId()));
    assertEquals(expected, StackState.count("stackId", ref.stackId()));
  }

  private void setBaseSnapshot(UUID stackId, long journalSeq, String resourcesJson) {
    requiringNew()
        .run(
            () -> {
              Snapshot snapshot = new Snapshot();
              snapshot.stackId = stackId;
              snapshot.version = 1;
              snapshot.checkpoint = deployment(resourcesJson);
              snapshot.compactedFromJournalSeq = journalSeq;
              snapshot.persistAndFlush();

              StackState state = StackState.find("stackId", stackId).firstResult();
              state.baseSnapshotId = snapshot.id;
              state.baseSnapshotVersion = snapshot.version;
              state.journalSeq = journalSeq;
              state.persistAndFlush();
            });
  }

  private static JsonNode deployment(String resourcesJson) {
    try {
      return MAPPER.readTree("{\"resources\":" + resourcesJson + "}");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Map<String, String> fixtureTags() {
    return Map.of(
        "pulumi:description",
        "Minimal Pulumi project for API recording - no resources, no providers.",
        "pulumi:project",
        PROJECT,
        "pulumi:runtime",
        "yaml");
  }
}
