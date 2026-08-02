package app.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the response shape of {@code GET /api/stacks/{org}/{project}/{stack}} against the recorded
 * fixture {@code tools/pulumi/recorder/fixtures/by-command/03-preview/0008_GET_api_stacks_...}.
 */
@QuarkusTest
class StackHeadContractTest {

  private static final String ORG_NAME = "stack-head-org";
  private static final String OTHER_ORG_NAME = "stack-head-other-org";
  private static final String RAW_TOKEN = "stack-head-token";
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
              org.name = ORG_NAME;
              org.displayName = "Stack Head Test";
              org.persistAndFlush();

              Organization other = new Organization();
              other.name = OTHER_ORG_NAME;
              other.displayName = "Other Org";
              other.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "stack-head";
              user.email = "head@example.com";
              user.displayName = "Stack Head Tester";
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
              t.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
              t.description = "stack-head";
              t.persistAndFlush();
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
                  .setParameter(1, ORG_NAME)
                  .setParameter(2, OTHER_ORG_NAME)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "stack-head")
                  .executeUpdate();
            });
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
        // Deferred fields must NOT appear on the Pulumi wire (Slice 12 / Slice 17 land them).
        .body("$", org.hamcrest.Matchers.not(hasKey("version")))
        .body("$", org.hamcrest.Matchers.not(hasKey("lastUpdate")))
        .body("$", org.hamcrest.Matchers.not(hasKey("resourceCount")));
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
  void crossOrgCollapsesTo404() {
    // Seed a stack owned by the *other* org directly via Hibernate.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization other =
                  Organization.find("name", OTHER_ORG_NAME).<Organization>firstResult();
              Project p = new Project();
              p.orgId = other.id;
              p.name = "secret-proj";
              p.persistAndFlush();
              Workspace w = new Workspace();
              w.orgId = other.id;
              w.kind = Workspace.Kind.pulumi;
              w.persistAndFlush();
              Stack s = new Stack();
              s.orgId = other.id;
              s.projectId = p.id;
              s.name = "secret";
              s.workspaceId = w.id;
              s.persistAndFlush();
              StackState st = new StackState();
              st.stackId = s.id;
              st.persistAndFlush();
            });

    // Token scoped to ORG_NAME must not see OTHER_ORG_NAME's stack — 404, not 403.
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/secret-proj/secret")
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
    UUID updateId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org =
                      Organization.<Organization>find("name", ORG_NAME).singleResult();
                  stackCreator.create(org.id, ORG_NAME, PROJECT, STACK, Map.of());
                  Project p =
                      Project.<Project>find("orgId = ?1 and name = ?2", org.id, PROJECT)
                          .singleResult();
                  Stack s =
                      Stack.<Stack>find("projectId = ?1 and name = ?2", p.id, STACK).singleResult();
                  StackUpdate u = new StackUpdate();
                  u.stackId = s.id;
                  u.kind = StackUpdate.Kind.UPDATE;
                  u.status = StackUpdate.Status.IN_PROGRESS;
                  u.version = 0;
                  u.dryRun = false;
                  u.startedAt = Instant.now();
                  u.persistAndFlush();
                  s.activeUpdateId = u.id;
                  return u.id;
                });

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + STACK)
        .then()
        .statusCode(200)
        .body("activeUpdate", equalTo(updateId.toString()));
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
