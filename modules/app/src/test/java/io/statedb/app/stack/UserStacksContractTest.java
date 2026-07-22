package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

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
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the response shape of {@code GET /api/user/stacks} against the recorded fixture {@code
 * tools/pulumi/recorder/fixtures/by-command/05-stack-ls/0004_GET_api_user_stacks.json}.
 */
@QuarkusTest
class UserStacksContractTest {

  private static final String ORG_A = "user-stacks-org-a";
  private static final String ORG_B = "user-stacks-org-b";
  private static final String ORG_EMPTY = "user-stacks-org-empty";
  private static final String TOKEN_A = "user-stacks-token-a";
  private static final String TOKEN_EMPTY = "user-stacks-token-empty";

  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization a = newOrg(ORG_A, "Org A");
              Organization b = newOrg(ORG_B, "Org B");
              Organization empty = newOrg(ORG_EMPTY, "Empty Org");

              User userA = newUser("user-stacks-a", "a@example.com");
              User userEmpty = newUser("user-stacks-empty", "empty@example.com");

              newMember(a.id, userA.id);
              newMember(empty.id, userEmpty.id);

              newToken(TOKEN_A, userA.id, a.id);
              newToken(TOKEN_EMPTY, userEmpty.id, empty.id);

              // Org A: two projects across two stacks, plus one extra to exercise ordering.
              //   project=alpha → stacks: prod, dev   (reverse-lex insertion)
              //   project=beta  → stacks: dev
              newStack(a.id, "alpha", "prod");
              newStack(a.id, "alpha", "dev");
              newStack(a.id, "beta", "dev");

              // Org B: one stack the token-A caller must never see.
              newStack(b.id, "secret", "dev");
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2, ?3)")
                  .setParameter(1, ORG_A)
                  .setParameter(2, ORG_B)
                  .setParameter(3, ORG_EMPTY)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub IN (?2, ?3)")
                  .setParameter(1, "local:test")
                  .setParameter(2, "user-stacks-a")
                  .setParameter(3, "user-stacks-empty")
                  .executeUpdate();
            });
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
        .body("stacks[0]", not(Matchers.hasKey("lastUpdate")))
        .body("stacks[0]", not(Matchers.hasKey("resourceCount")));
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

  // --- seed helpers ----------------------------------------------------------------------------

  private static Organization newOrg(String name, String displayName) {
    Organization o = new Organization();
    o.name = name;
    o.displayName = displayName;
    o.persistAndFlush();
    return o;
  }

  private static User newUser(String sub, String email) {
    User u = new User();
    u.iss = "local:test";
    u.sub = sub;
    u.email = email;
    u.displayName = sub;
    u.persistAndFlush();
    return u;
  }

  private static void newMember(UUID orgId, UUID userId) {
    OrgMember m = new OrgMember();
    m.orgId = orgId;
    m.userId = userId;
    m.role = OrgMember.Role.owner;
    m.persistAndFlush();
  }

  private static void newToken(String raw, UUID userId, UUID orgId) {
    ApiToken t = new ApiToken();
    t.tokenHash = sha256Hex(raw);
    t.userId = userId;
    t.orgId = orgId;
    t.kind = ApiToken.Kind.service_account;
    t.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
    t.description = "user-stacks-test";
    t.persistAndFlush();
  }

  private static void newStack(UUID orgId, String projectName, String stackName) {
    Project p = Project.find("orgId = ?1 and name = ?2", orgId, projectName).firstResult();
    if (p == null) {
      p = new Project();
      p.orgId = orgId;
      p.name = projectName;
      p.persistAndFlush();
    }
    Workspace w = new Workspace();
    w.orgId = orgId;
    w.kind = Workspace.Kind.pulumi;
    w.persistAndFlush();
    Stack s = new Stack();
    s.orgId = orgId;
    s.projectId = p.id;
    s.name = stackName;
    s.workspaceId = w.id;
    s.persistAndFlush();
    StackState st = new StackState();
    st.stackId = s.id;
    st.persistAndFlush();
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
