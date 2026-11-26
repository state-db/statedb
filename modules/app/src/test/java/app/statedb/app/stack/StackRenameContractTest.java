package app.statedb.app.stack;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import app.statedb.app.contract.PulumiContractIdentity;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code POST /api/stacks/{org}/{project}/{stack}/rename} — the endpoint behind {@code pulumi
 * stack rename}. The CLI sends {@code newName}/{@code newProject} (empty string = preserve current)
 * and ignores the body, so the move must surface through {@code GET stack} at the new name and 404
 * at the old one.
 */
@QuarkusTest
class StackRenameContractTest {

  private static final String ORG_NAME = "stack-rename-org";
  private static final String RAW_TOKEN = "stack-rename-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT;

  private static final PulumiContractIdentity IDENTITY =
      new PulumiContractIdentity(
          ORG_NAME,
          "Stack Rename Test",
          "stack-rename",
          "rename@example.com",
          "Stack Rename Tester",
          RAW_TOKEN,
          "stack-rename");

  @BeforeEach
  void seed() {
    IDENTITY.seed();

    createStack(STACK);
  }

  @AfterEach
  void cleanup() {
    IDENTITY.cleanup();
  }

  @Test
  void renamesStackWithinProject() {
    // `pulumi stack rename staging` sends newProject = the current project. The body matches the
    // recorded AppImportStackResponse shape, carrying the recorded rename update's id.
    rename(STACK, Map.of("newName", "staging", "newProject", PROJECT))
        .then()
        .statusCode(200)
        .body("updateId", not(equalTo("")));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/staging")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/" + STACK)
        .then()
        .statusCode(404);
  }

  @Test
  void renameOutsideNameGrammarReturns400() {
    rename(STACK, Map.of("newName", "prod/web", "newProject", PROJECT)).then().statusCode(400);
    rename(STACK, Map.of("newName", "staging", "newProject", "bad/proj")).then().statusCode(400);

    // The stack is untouched at its original name.
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/" + STACK)
        .then()
        .statusCode(200);
  }

  @Test
  void renameToExistingTargetReturns409() {
    createStack("staging");

    rename(STACK, Map.of("newName", "staging", "newProject", PROJECT)).then().statusCode(409);
  }

  @Test
  void rewritesCheckpointUrnsAndSecretsStateSameProject() {
    importDeployment(STACK, "dev", PROJECT);

    rename(STACK, Map.of("newName", "staging", "newProject", PROJECT)).then().statusCode(200);

    // The stored checkpoint must follow the rename: the service secrets provider (used to route
    // batch-decrypt) and every resource URN now name the new stack, or `pulumi stack output` 404s.
    String root =
        "urn:pulumi:staging::" + PROJECT + "::pulumi:pulumi:Stack::" + PROJECT + "-staging";
    String provider = "urn:pulumi:staging::" + PROJECT + "::pulumi:providers:random::default";
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/staging/export")
        .then()
        .statusCode(200)
        .body("deployment.secrets_providers.state.stack", equalTo("staging"))
        .body("deployment.secrets_providers.state.project", equalTo(PROJECT))
        .body("deployment.resources[0].urn", equalTo(root))
        .body("deployment.resources[1].urn", equalTo(provider))
        .body(
            "deployment.resources[2].urn",
            equalTo(
                "urn:pulumi:staging::"
                    + PROJECT
                    + "::random:index/randomString:RandomString::word"))
        .body("deployment.resources[2].parent", equalTo(root))
        .body("deployment.resources[2].provider", equalTo(provider + "::prov-id"))
        .body("deployment.resources[2].dependencies[0]", equalTo(root));
  }

  @Test
  void crossProjectRenameRewritesProjectComponent() {
    importDeployment(STACK, "dev", PROJECT);

    rename(STACK, Map.of("newName", "staging", "newProject", "moved")).then().statusCode(200);

    String movedBase = "/pulumi/api/stacks/" + ORG_NAME + "/moved";
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(movedBase + "/staging/export")
        .then()
        .statusCode(200)
        .body("deployment.secrets_providers.state.project", equalTo("moved"))
        .body("deployment.secrets_providers.state.stack", equalTo("staging"))
        .body(
            "deployment.resources[0].urn",
            equalTo("urn:pulumi:staging::moved::pulumi:pulumi:Stack::moved-staging"))
        .body(
            "deployment.resources[2].urn",
            equalTo("urn:pulumi:staging::moved::random:index/randomString:RandomString::word"));
  }

  @Test
  void renameRecordsRenameUpdate() {
    String updateId =
        rename(STACK, Map.of("newName", "staging", "newProject", PROJECT))
            .then()
            .statusCode(200)
            .extract()
            .path("updateId");

    // The returned id must point at a terminal `rename` update — the audit record of the move.
    tx(
        () -> {
          StackUpdate update = StackUpdate.findById(UUID.fromString(updateId));
          org.junit.jupiter.api.Assertions.assertNotNull(update, "rename recorded no update");
          org.junit.jupiter.api.Assertions.assertEquals(StackUpdate.Kind.RENAME, update.kind);
          org.junit.jupiter.api.Assertions.assertEquals(
              StackUpdate.Status.SUCCEEDED, update.status);
        });
  }

  @Test
  void crossProjectRenameUpdatesProjectTag() {
    // Pulumi sets the reserved pulumi:project tag; it must follow a cross-project move so GET
    // doesn't report a project that contradicts the stack's actual project.
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("pulumi:project", PROJECT, "pulumi:runtime", "yaml"))
        .when()
        .patch(BASE + "/" + STACK + "/tags")
        .then()
        .statusCode(200);

    rename(STACK, Map.of("newName", "staging", "newProject", "moved")).then().statusCode(200);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/moved/staging")
        .then()
        .statusCode(200)
        .body("tags.\"pulumi:project\"", equalTo("moved"))
        .body("tags.\"pulumi:runtime\"", equalTo("yaml"));
  }

  @Test
  void emptyBodyReturns400() {
    rename(STACK, Map.of("newName", "", "newProject", "")).then().statusCode(400);
  }

  @Test
  void missingStackReturns404() {
    rename("does-not-exist", Map.of("newName", "staging", "newProject", PROJECT))
        .then()
        .statusCode(404);
  }

  @Test
  void missingTokenReturns401() {
    given()
        .contentType("application/json")
        .body(Map.of("newName", "staging", "newProject", PROJECT))
        .when()
        .post(BASE + "/" + STACK + "/rename")
        .then()
        .statusCode(401);
  }

  private void createStack(String name) {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("stackName", name))
        .when()
        .post(BASE)
        .then()
        .statusCode(200);
  }

  // Seed real state via the import endpoint: a service secrets provider plus a small resource graph
  // (root stack, default provider, a resource with parent/provider/dependency URNs) all naming the
  // current stack/project, so a rename has something to rewrite.
  private void importDeployment(String stack, String urnStack, String urnProject) {
    String root =
        "urn:pulumi:"
            + urnStack
            + "::"
            + urnProject
            + "::pulumi:pulumi:Stack::"
            + urnProject
            + "-"
            + urnStack;
    String provider =
        "urn:pulumi:" + urnStack + "::" + urnProject + "::pulumi:providers:random::default";
    String word =
        "urn:pulumi:"
            + urnStack
            + "::"
            + urnProject
            + "::random:index/randomString:RandomString::word";
    String deployment =
        """
        {
          "version": 3,
          "deployment": {
            "manifest": {"time": "2026-01-01T00:00:00Z", "magic": "deadbeef", "version": "v3.0.0"},
            "secrets_providers": {
              "type": "service",
              "state": {"url": "http://localhost:8081/pulumi", "owner": "%s", "project": "%s", "stack": "%s"}
            },
            "resources": [
              {"urn": "%s", "type": "pulumi:pulumi:Stack", "custom": false},
              {"urn": "%s", "type": "pulumi:providers:random", "custom": true, "id": "prov-id", "parent": "%s"},
              {"urn": "%s", "type": "random:index/randomString:RandomString", "custom": true, "id": "abc",
               "parent": "%s", "provider": "%s::prov-id", "dependencies": ["%s"]}
            ]
          }
        }
        """
            .formatted(
                ORG_NAME, urnProject, urnStack, root, provider, root, word, root, provider, root);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(deployment)
        .when()
        .post(BASE + "/" + stack + "/import")
        .then()
        .statusCode(200);
  }

  private io.restassured.response.Response rename(String stack, Map<String, String> body) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post(BASE + "/" + stack + "/rename");
  }
}
