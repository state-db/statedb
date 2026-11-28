package app.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the response shape of {@code POST /api/stacks/{org}/{project}} against the recorded fixture
 * under {@code tools/pulumi/recorder/fixtures/by-command/02-stack-init/}.
 */
@QuarkusTest
class StackCreateContractTest extends DatabaseTest {

  private static final String ORG_NAME = "stack-create-org";
  private static final String OTHER_ORG_NAME = "stack-create-other-org";
  private static final String RAW_TOKEN = "stack-create-token";
  private static final String PROJECT = "recorder-test";

  @BeforeEach
  void seed() {
    db.seedOwner(ORG_NAME, RAW_TOKEN);
    db.seedOrg(OTHER_ORG_NAME);
  }

  @Test
  void createsStackAndReturnsPulumiShape() {
    Map<String, Object> body =
        Map.of(
            "stackName",
            "dev",
            "tags",
            Map.of(
                "pulumi:description", "test",
                "pulumi:project", PROJECT,
                "pulumi:runtime", "yaml"));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .body(body)
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT)
        .then()
        .statusCode(200)
        .body("activeUpdate", equalTo(""))
        .body("currentOperation", is(nullValue()))
        .body("orgName", equalTo(ORG_NAME))
        .body("projectName", equalTo(PROJECT))
        .body("stackName", equalTo("dev"))
        .body("tags.\"pulumi:project\"", equalTo(PROJECT))
        .body("tags.\"pulumi:runtime\"", equalTo("yaml"));

    // The workspace, stack, and stack_state rows must all have been created.
    Number stacks =
        (Number)
            em.createNativeQuery(
                    "SELECT count(*) FROM stacks s "
                        + "JOIN projects p ON p.id = s.project_id "
                        + "JOIN organizations o ON o.id = s.org_id "
                        + "WHERE o.name = ?1 AND p.name = ?2 AND s.name = ?3")
                .setParameter(1, ORG_NAME)
                .setParameter(2, PROJECT)
                .setParameter(3, "dev")
                .getSingleResult();
    assertEquals(1L, stacks.longValue());

    Number states =
        (Number)
            em.createNativeQuery(
                    "SELECT count(*) FROM stack_state ss "
                        + "JOIN stacks s ON s.id = ss.stack_id "
                        + "JOIN projects p ON p.id = s.project_id "
                        + "JOIN organizations o ON o.id = s.org_id "
                        + "WHERE o.name = ?1 AND p.name = ?2 AND s.name = ?3")
                .setParameter(1, ORG_NAME)
                .setParameter(2, PROJECT)
                .setParameter(3, "dev")
                .getSingleResult();
    assertEquals(1L, states.longValue());
  }

  @Test
  void stackNameOutsideGrammarReturns400() {
    // A '/' in a name would collide Cedar resource ids (they join org/project/stack with '/'),
    // so the grammar rejects it before any row is written.
    Map<String, Object> body = Map.of("stackName", "prod/web", "tags", Map.of());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT)
        .then()
        .statusCode(400);
  }

  @Test
  void missingStackNameReturnsPulumiError() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("tags", Map.of()))
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT)
        .then()
        .statusCode(400)
        .body("code", equalTo(400))
        .body("message", equalTo("stackName is required"));
  }

  @Test
  void projectNameOutsideGrammarReturns400() {
    // '~' is URL-safe (reaches the handler as a path segment) but outside the name grammar.
    Map<String, Object> body = Map.of("stackName", "dev", "tags", Map.of());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/bad~proj")
        .then()
        .statusCode(400);
  }

  @Test
  void duplicateStackReturns409() {
    Map<String, Object> body = Map.of("stackName", "dup", "tags", Map.of());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT)
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT)
        .then()
        .statusCode(409);
  }

  @Test
  void crossOrgGetCollapsesTo404() {
    db.seedStack(OTHER_ORG_NAME, "other-proj", "secret");

    // A token scoped to ORG_NAME must not see the stack in OTHER_ORG_NAME — collapse to 404,
    // not 403 (a 403 would confirm existence in another tenant).
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/other-proj/secret")
        .then()
        .statusCode(404);
  }

  @Test
  void orgMismatchReturns403() {
    Map<String, Object> body = Map.of("stackName", "dev", "tags", Map.of());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/" + PROJECT)
        .then()
        .statusCode(403);
  }
}
