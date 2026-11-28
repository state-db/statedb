package app.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the start-op endpoints (POST .../preview, .../update, .../destroy). Pins the
 * {@code {updateID, version}} wire shape against the recorded fixtures under {@code
 * tools/pulumi/recorder/fixtures/by-command/03-preview/} and 04-up / 07-destroy.
 */
@QuarkusTest
class StartUpdateContractTest extends DatabaseTest {

  private static final String ORG = "start-update-org";
  private static final String OTHER_ORG = "start-update-other-org";
  private static final String RAW_TOKEN = "start-update-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  private UUID stackId;

  @BeforeEach
  void seed() {
    stackId = db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK).stack().stackId();
    db.seedStack(OTHER_ORG, PROJECT, STACK);
  }

  @Test
  void previewReturnsUpdateIdAndPersistsDryRunRow() {
    // Previews are dry runs: they never consume the applied-version sequence (version 0).
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(programBody())
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(200)
        .body("updateID", notNullValue())
        .body("version", equalTo(0));

    Object[] row =
        (Object[])
            em.createNativeQuery(
                    "SELECT kind, status, pulumi_version, version, dry_run"
                        + " FROM stack_updates WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals("preview", row[0]);
    assertEquals("in-progress", row[1]);
    assertEquals("v3.234.0", row[2]);
    assertEquals(0, ((Number) row[3]).intValue());
    assertEquals(Boolean.TRUE, row[4]);
  }

  @Test
  void updateAndDestroyAlsoSucceed() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(applyBody())
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update")
        .then()
        .statusCode(200)
        .body("version", equalTo(1));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(applyBody())
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/destroy")
        .then()
        .statusCode(200)
        .body("version", equalTo(2));

    @SuppressWarnings("unchecked")
    List<String> kinds =
        em.createNativeQuery("SELECT kind FROM stack_updates WHERE stack_id = ?1 ORDER BY version")
            .setParameter(1, stackId)
            .getResultList();
    assertEquals(List.of("update", "destroy"), kinds);
  }

  @Test
  void idempotencyKeyReturnsSameUpdateIdAndOneRow() {
    String firstUpdateId =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .header("Idempotency-Key", "same-key")
            .contentType("application/json")
            .body(programBody())
            .when()
            .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
            .then()
            .statusCode(200)
            .extract()
            .path("updateID");

    String secondUpdateId =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .header("Idempotency-Key", "same-key")
            .contentType("application/json")
            .body(programBody())
            .when()
            .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
            .then()
            .statusCode(200)
            .extract()
            .path("updateID");

    assertEquals(firstUpdateId, secondUpdateId);
    assertEquals(1L, StackUpdate.count("stackId", stackId));
  }

  @Test
  void crossOrgCollapsesTo404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(programBody())
        .when()
        .post("/pulumi/api/stacks/" + OTHER_ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(404);
  }

  @Test
  void projectNameMismatchReturns400InsteadOfUsingPathContext() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(programBody("other-project"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(400)
        .body("message", containsString("name must match the stack path"));
  }

  @Test
  void explicitOrgProjectAndStackFieldsMustMatchPath() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(
            Map.of(
                "name", PROJECT,
                "orgName", ORG,
                "projectName", PROJECT,
                "stackName", "other-stack",
                "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(400)
        .body("message", containsString("stackName must match the stack path"));
  }

  @Test
  void oldPulumiVersionIsRejectedBeforeAnyUpdateRowOrLockExists() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(programBody(PROJECT, true, "v3.100.0"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(400)
        .body("message", containsString("does not support journaling"))
        .body("message", containsString("3.100.0"));

    assertEquals(0L, StackUpdate.count("stackId", stackId));
  }

  private static Map<String, Object> programBody() {
    return programBody(PROJECT, true);
  }

  private static Map<String, Object> programBody(String projectName) {
    return programBody(projectName, true);
  }

  private static Map<String, Object> applyBody() {
    return programBody(PROJECT, false);
  }

  private static Map<String, Object> programBody(String projectName, boolean dryRun) {
    return programBody(projectName, dryRun, "v3.234.0");
  }

  private static Map<String, Object> programBody(
      String projectName, boolean dryRun, String pulumiVersion) {
    return Map.of(
        "config",
        Map.of(),
        "description",
        "Minimal Pulumi project for API recording.",
        "main",
        "",
        "metadata",
        Map.of(
            "environment",
            Map.of(
                "exec.kind", "cli",
                "pulumi.arch", "arm64",
                "pulumi.os", "linux",
                "pulumi.version", pulumiVersion,
                "runtime.name", "yaml"),
            "message",
            ""),
        "name",
        projectName,
        "options",
        Map.of("color", "raw", "debug", false, "dryRun", dryRun, "parallel", 40),
        "runtime",
        "yaml");
  }
}
