package io.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.StackCreator;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the start-op endpoints (POST .../preview, .../update, .../destroy). Pins the
 * {@code {updateID, version}} wire shape against the recorded fixtures under {@code
 * tools/pulumi/recorder/fixtures/by-command/03-preview/} and 04-up / 07-destroy.
 */
@QuarkusTest
class StartUpdateContractTest {

  private static final String ORG = "start-update-org";
  private static final String OTHER_ORG = "start-update-other-org";
  private static final String RAW_TOKEN = "start-update-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  @Inject EntityManager em;
  @Inject StackCreator stackCreator;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = "Start Update Test";
              org.persistAndFlush();

              Organization other = new Organization();
              other.name = OTHER_ORG;
              other.displayName = "Other Org";
              other.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "start-update";
              user.email = "start@example.com";
              user.displayName = "Start Update Tester";
              user.persistAndFlush();

              OrgMember m = new OrgMember();
              m.orgId = org.id;
              m.userId = user.id;
              m.role = OrgMember.Role.owner;
              m.persistAndFlush();

              ApiToken t = new ApiToken();
              t.tokenHash = sha256Hex(RAW_TOKEN);
              t.userId = user.id;
              t.orgId = org.id;
              t.kind = ApiToken.Kind.service_account;
              t.description = "start-update";
              t.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
              stackCreator.create(other.id, OTHER_ORG, PROJECT, STACK, Map.of());
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
                  .setParameter(1, ORG)
                  .setParameter(2, OTHER_ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "start-update")
                  .executeUpdate();
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Object[] row =
                  (Object[])
                      em.createNativeQuery(
                              "SELECT kind, status, pulumi_version, version, dry_run"
                                  + " FROM stack_updates WHERE stack_id = ?1")
                          .setParameter(1, stackId(ORG))
                          .getSingleResult();
              assertEquals("preview", row[0]);
              assertEquals("in-progress", row[1]);
              assertEquals("v3.234.0", row[2]);
              assertEquals(0, ((Number) row[3]).intValue());
              assertEquals(Boolean.TRUE, row[4]);
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              @SuppressWarnings("unchecked")
              List<String> kinds =
                  em.createNativeQuery(
                          "SELECT kind FROM stack_updates WHERE stack_id = ?1 ORDER BY version")
                      .setParameter(1, stackId(ORG))
                      .getResultList();
              assertEquals(List.of("update", "destroy"), kinds);
            });
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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Number n =
                  (Number)
                      em.createNativeQuery("SELECT count(*) FROM stack_updates WHERE stack_id = ?1")
                          .setParameter(1, stackId(ORG))
                          .getSingleResult();
              assertEquals(1L, n.longValue());
            });
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

  private UUID stackId(String orgName) {
    return (UUID)
        em.createNativeQuery(
                "SELECT s.id FROM stacks s"
                    + " JOIN projects p ON p.id = s.project_id"
                    + " JOIN organizations o ON o.id = s.org_id"
                    + " WHERE o.name = ?1 AND p.name = ?2 AND s.name = ?3")
            .setParameter(1, orgName)
            .setParameter(2, PROJECT)
            .setParameter(3, STACK)
            .getSingleResult();
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
                "pulumi.version", "v3.234.0",
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
