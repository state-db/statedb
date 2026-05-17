package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.Workspace;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the response shape of {@code POST /api/stacks/{org}/{project}} against the recorded
 * fixture under {@code tools/pulumi/recorder/fixtures/by-command/02-stack-init/}.
 */
@QuarkusTest
class StackCreateContractTest {

  private static final String ORG_NAME = "stack-create-org";
  private static final String OTHER_ORG_NAME = "stack-create-other-org";
  private static final String RAW_TOKEN = "stack-create-token";
  private static final String PROJECT = "recorder-test";

  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Stack Create Test";
              org.persistAndFlush();

              Organization other = new Organization();
              other.name = OTHER_ORG_NAME;
              other.displayName = "Other Org";
              other.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "stack-create";
              user.email = "stack@example.com";
              user.displayName = "Stack Create Tester";
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
              t.description = "stack-create";
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
                  .setParameter(2, "stack-create")
                  .executeUpdate();
            });
  }

  @Test
  void createsStackAndReturnsPulumiShape() {
    Map<String, Object> body =
        Map.of(
            "stackName", "dev",
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
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
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
              org.junit.jupiter.api.Assertions.assertEquals(1L, stacks.longValue());

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
              org.junit.jupiter.api.Assertions.assertEquals(1L, states.longValue());
            });
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
    // Seed a stack owned by the *other* org directly via Hibernate (skipping the auth-gated POST).
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization other =
                  Organization.find("name", OTHER_ORG_NAME).<Organization>firstResult();
              Project p = new Project();
              p.orgId = other.id;
              p.name = "other-proj";
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
