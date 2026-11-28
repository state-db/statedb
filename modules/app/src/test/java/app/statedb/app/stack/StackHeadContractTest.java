package app.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the response shape of {@code GET /api/stacks/{org}/{project}/{stack}} against the recorded
 * fixture {@code tools/pulumi/recorder/fixtures/by-command/03-preview/0008_GET_api_stacks_...}.
 */
@QuarkusTest
class StackHeadContractTest extends DatabaseTest {

  private static final String ORG_NAME = "stack-head-org";
  private static final String OTHER_ORG_NAME = "stack-head-other-org";
  private static final String RAW_TOKEN = "stack-head-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  @BeforeEach
  void seed() {
    db.seedOwner(ORG_NAME, RAW_TOKEN);
    db.seedOrg(OTHER_ORG_NAME);
  }

  @Test
  void getReturnsPulumiShape() {
    Map<String, Object> body =
        Map.of(
            "stackName",
            STACK,
            "tags",
            Map.of(
                "pulumi:description", "test desc",
                "pulumi:project", PROJECT,
                "pulumi:runtime", "yaml"));

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
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + STACK)
        .then()
        .statusCode(200)
        .body("activeUpdate", equalTo(""))
        .body("currentOperation", is(nullValue()))
        .body("orgName", equalTo(ORG_NAME))
        .body("projectName", equalTo(PROJECT))
        .body("stackName", equalTo(STACK))
        .body("tags.\"pulumi:project\"", equalTo(PROJECT))
        .body("tags.\"pulumi:runtime\"", equalTo("yaml"))
        .body("tags.\"pulumi:description\"", equalTo("test desc"))
        // Deferred fields must NOT appear on the Pulumi wire.
        .body("$", not(hasKey("version")))
        .body("$", not(hasKey("lastUpdate")))
        .body("$", not(hasKey("resourceCount")));
  }

  @Test
  void missingStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/does-not-exist")
        .then()
        .statusCode(404);
  }

  @Test
  void sameNamesAcrossOrgsResolveOwnStackAndHideOther() {
    db.seedStack(ORG_NAME, "shared-proj", "shared-stack", Map.of("tenant", "authorized"));
    db.seedStack(OTHER_ORG_NAME, "shared-proj", "shared-stack", Map.of("tenant", "hidden"));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/shared-proj/shared-stack")
        .then()
        .statusCode(200)
        .body("orgName", equalTo(ORG_NAME))
        .body("tags.tenant", equalTo("authorized"));

    // The same project and stack names exist in the other org, but its metadata stays hidden.
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/shared-proj/shared-stack")
        .then()
        .statusCode(404);
  }

  @Test
  void missingTokenReturns401() {
    given()
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + STACK)
        .then()
        .statusCode(401);
  }

  @Test
  void activeUpdateReturnsCurrentUpdateId() {
    UUID stackId = db.seedStack(ORG_NAME, PROJECT, STACK).stackId();
    UUID updateId = db.seedActiveUpdate(stackId, StackUpdate.Kind.UPDATE).updateId();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + STACK)
        .then()
        .statusCode(200)
        .body("activeUpdate", equalTo(updateId.toString()));
  }
}
