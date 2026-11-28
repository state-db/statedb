package app.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import app.statedb.app.DatabaseTest;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the response shape of {@code GET /api/user/stacks} against the recorded fixture {@code
 * tools/pulumi/recorder/fixtures/by-command/05-stack-ls/0004_GET_api_user_stacks.json}.
 */
@QuarkusTest
class UserStacksContractTest extends DatabaseTest {

  private static final String ORG_A = "user-stacks-org-a";
  private static final String ORG_B = "user-stacks-org-b";
  private static final String ORG_EMPTY = "user-stacks-org-empty";
  private static final String TOKEN_A = "user-stacks-token-a";
  private static final String TOKEN_EMPTY = "user-stacks-token-empty";

  @BeforeEach
  void seed() {
    db.seedOwner(ORG_A, TOKEN_A);
    db.seedOwner(ORG_EMPTY, TOKEN_EMPTY);

    // Org A: two projects across two stacks, plus one extra to exercise ordering.
    //   project=alpha -> stacks: prod, dev   (reverse-lex insertion)
    //   project=beta  -> stacks: dev
    db.seedStack(ORG_A, "alpha", "prod");
    db.seedStack(ORG_A, "alpha", "dev");
    db.seedStack(ORG_A, "beta", "dev");

    // Org B: one stack the token-A caller must never see.
    db.seedStack(ORG_B, "secret", "dev");
  }

  @Test
  void listScopedToOrgWithStableOrdering() {
    given()
        .header("Authorization", "token " + TOKEN_A)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user/stacks")
        .then()
        .statusCode(200)
        .body("stacks", hasSize(3))
        // Ordering: (orgName, projectName, stackName) — alpha/dev, alpha/prod, beta/dev.
        .body("stacks[0].id", equalTo(ORG_A + "/alpha/dev"))
        .body("stacks[0].orgName", equalTo(ORG_A))
        .body("stacks[0].projectName", equalTo("alpha"))
        .body("stacks[0].stackName", equalTo("dev"))
        .body("stacks[1].id", equalTo(ORG_A + "/alpha/prod"))
        .body("stacks[2].id", equalTo(ORG_A + "/beta/dev"))
        .body("stacks[0]", not(hasKey("lastUpdate")))
        .body("stacks[0]", not(hasKey("resourceCount")));
  }

  @Test
  void projectFilterNarrowsResult() {
    given()
        .header("Authorization", "token " + TOKEN_A)
        .when()
        .get("/pulumi/api/user/stacks?project=alpha")
        .then()
        .statusCode(200)
        .body("stacks", hasSize(2))
        .body("stacks[0].id", equalTo(ORG_A + "/alpha/dev"))
        .body("stacks[1].id", equalTo(ORG_A + "/alpha/prod"));
  }

  @Test
  void emptyOrgReturnsEmptyList() {
    given()
        .header("Authorization", "token " + TOKEN_EMPTY)
        .when()
        .get("/pulumi/api/user/stacks")
        .then()
        .statusCode(200)
        .body("stacks", empty());
  }

  @Test
  void missingTokenReturns401() {
    given().when().get("/pulumi/api/user/stacks").then().statusCode(401);
  }
}
