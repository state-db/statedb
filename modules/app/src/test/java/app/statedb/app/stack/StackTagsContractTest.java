package app.statedb.app.stack;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code PATCH /api/stacks/{org}/{project}/{stack}/tags} — the endpoint behind {@code pulumi
 * stack tag set}/{@code rm}. The CLI sends the full desired tag map (replace, not merge), so a set
 * and a subsequent remove must both round-trip through {@code GET stack}.
 */
@QuarkusTest
class StackTagsContractTest extends DatabaseTest {

  private static final String ORG_NAME = "stack-tags-org";
  private static final String RAW_TOKEN = "stack-tags-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT;

  private UUID stackId;

  @BeforeEach
  void seed() {
    db.seedOwner(ORG_NAME, RAW_TOKEN);
    stackId =
        db.seedStack(
                ORG_NAME,
                PROJECT,
                STACK,
                Map.of("pulumi:project", PROJECT, "pulumi:runtime", "yaml"))
            .stackId();
  }

  @Test
  void patchReplacesTagsWholesale() {
    // `stack tag set env staging` sends every existing tag plus the new one.
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(
            Map.of(
                "pulumi:project", PROJECT,
                "pulumi:runtime", "yaml",
                "env", "staging"))
        .when()
        .patch(BASE + "/" + STACK + "/tags")
        .then()
        .statusCode(200)
        .body("$", equalTo(Map.of()));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/" + STACK)
        .then()
        .statusCode(200)
        .body("tags.env", equalTo("staging"))
        .body("tags.\"pulumi:runtime\"", equalTo("yaml"));

    // `stack tag rm env` sends the map without env — replace semantics drop it.
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("pulumi:project", PROJECT, "pulumi:runtime", "yaml"))
        .when()
        .patch(BASE + "/" + STACK + "/tags")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/" + STACK)
        .then()
        .statusCode(200)
        .body("tags", not(hasKey("env")))
        .body("tags.\"pulumi:project\"", equalTo(PROJECT));
  }

  @Test
  void tagWriteDoesNotClobberActiveUpdate() {
    // Stand in for a concurrent `pulumi up`: an in-progress update with the stack pointing at it.
    db.seedActiveUpdate(stackId, StackUpdate.Kind.UPDATE);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("pulumi:project", PROJECT, "pulumi:runtime", "yaml", "env", "staging"))
        .when()
        .patch(BASE + "/" + STACK + "/tags")
        .then()
        .statusCode(200);

    // The active-update pointer set by the "concurrent" update must survive the tag write — a
    // full-row entity update would have rewritten active_update_id back to its stale (null) value.
    requiringNew()
        .run(
            () -> {
              Stack s = Stack.findById(stackId);
              assertNotNull(s.activeUpdateId, "tag write cleared active_update_id");
              assertEquals("staging", s.tags.get("env"));
            });
  }

  @Test
  void rejectsTagLimitViolations() {
    // Limits mirror Pulumi's pkg/util/validation/stack.go: names are 1-40 chars of
    // [a-zA-Z0-9-_.:], values are capped at 256.

    // Too many tags.
    Map<String, String> tooMany = new HashMap<>();
    for (int i = 0; i < 51; i++) {
      tooMany.put("k" + i, "v");
    }
    patchTags(tooMany).then().statusCode(400);

    // Key over 40 characters.
    patchTags(Map.of("k".repeat(41), "v")).then().statusCode(400);

    // Key with characters outside Pulumi's tag-name alphabet.
    patchTags(Map.of("bad key", "v")).then().statusCode(400);

    // Value over 256 characters.
    patchTags(Map.of("env", "v".repeat(257))).then().statusCode(400);

    // At the limits — accepted.
    patchTags(Map.of("k".repeat(40), "v".repeat(256))).then().statusCode(200);
  }

  private Response patchTags(Map<String, String> tags) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(tags)
        .when()
        .patch(BASE + "/" + STACK + "/tags");
  }

  @Test
  void missingStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("env", "x"))
        .when()
        .patch(BASE + "/does-not-exist/tags")
        .then()
        .statusCode(404);
  }

  @Test
  void missingTokenReturns401() {
    given()
        .contentType("application/json")
        .body(Map.of("env", "x"))
        .when()
        .patch(BASE + "/" + STACK + "/tags")
        .then()
        .statusCode(401);
  }
}
