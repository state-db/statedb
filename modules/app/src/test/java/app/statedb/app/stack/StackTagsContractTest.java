package app.statedb.app.stack;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import app.statedb.app.contract.PulumiContractIdentity;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code PATCH /api/stacks/{org}/{project}/{stack}/tags} — the endpoint behind {@code pulumi
 * stack tag set}/{@code rm}. The CLI sends the full desired tag map (replace, not merge), so a set
 * and a subsequent remove must both round-trip through {@code GET stack}.
 */
@QuarkusTest
class StackTagsContractTest {

  private static final String ORG_NAME = "stack-tags-org";
  private static final String RAW_TOKEN = "stack-tags-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT;

  private static final PulumiContractIdentity IDENTITY =
      new PulumiContractIdentity(
          ORG_NAME,
          "Stack Tags Test",
          "stack-tags",
          "tags@example.com",
          "Stack Tags Tester",
          RAW_TOKEN,
          "stack-tags");

  @BeforeEach
  void seed() {
    IDENTITY.seed();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(
            Map.of(
                "stackName",
                STACK,
                "tags",
                Map.of("pulumi:project", PROJECT, "pulumi:runtime", "yaml")))
        .when()
        .post(BASE)
        .then()
        .statusCode(200);
  }

  @AfterEach
  void cleanup() {
    IDENTITY.cleanup();
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
        .body("tags", not(Matchers.hasKey("env")))
        .body("tags.\"pulumi:project\"", equalTo(PROJECT));
  }

  @Test
  void tagWriteDoesNotClobberActiveUpdate() {
    // Stand in for a concurrent `pulumi up`: an in-progress update with the stack pointing at it.
    UUID stackId =
        read(
            () -> {
              Stack s =
                  Stack.<Stack>find(
                          "select s from Stack s join s.project p join p.organization o"
                              + " where o.name = ?1 and p.name = ?2 and s.name = ?3",
                          ORG_NAME,
                          PROJECT,
                          STACK)
                      .firstResult();

              StackUpdate u = new StackUpdate();
              u.stackId = s.id;
              u.version = 1;
              u.kind = StackUpdate.Kind.UPDATE;
              u.status = StackUpdate.Status.IN_PROGRESS;
              u.startedAt = Instant.now();
              u.persistAndFlush();

              Stack.update("activeUpdateId = ?1 where id = ?2", u.id, s.id);
              return s.id;
            });

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
    tx(
        () -> {
          Stack s = Stack.findById(stackId);
          org.junit.jupiter.api.Assertions.assertNotNull(
              s.activeUpdateId, "tag write cleared active_update_id");
          org.junit.jupiter.api.Assertions.assertEquals("staging", s.tags.get("env"));
        });
  }

  @Test
  void rejectsTagLimitViolations() {
    // Limits mirror Pulumi's pkg/util/validation/stack.go: names are 1-40 chars of
    // [a-zA-Z0-9-_.:], values are capped at 256.

    // Too many tags.
    java.util.Map<String, String> tooMany = new java.util.HashMap<>();
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

  private io.restassured.response.Response patchTags(Map<String, String> tags) {
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
