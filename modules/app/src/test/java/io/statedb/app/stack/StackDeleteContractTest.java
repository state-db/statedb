package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.Workspace;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code DELETE /api/stacks/{org}/{project}/{stack}} against the recorded fixture under {@code
 * tools/pulumi/recorder/fixtures/by-command/08-stack-rm/}.
 */
@QuarkusTest
class StackDeleteContractTest {

  private static final String ORG_NAME = "stack-delete-org";
  private static final String OTHER_ORG_NAME = "stack-delete-other-org";
  private static final String RAW_TOKEN = "stack-delete-token";
  private static final String PROJECT = "recorder-test";

  @Inject EntityManager em;

  @Inject StackCreator stackCreator;

  private UUID orgId;

  @BeforeEach
  void seed() {
    orgId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Stack Delete Test";
                  org.persistAndFlush();

                  Organization other = new Organization();
                  other.name = OTHER_ORG_NAME;
                  other.displayName = "Other Org";
                  other.persistAndFlush();

                  User user = new User();
                  user.iss = "local:test";
                  user.sub = "stack-delete";
                  user.email = "delete@example.com";
                  user.displayName = "Stack Delete Tester";
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
                  t.description = "stack-delete";
                  t.persistAndFlush();

                  return org.id;
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
                  .setParameter(2, "stack-delete")
                  .executeUpdate();
            });
  }

  @Test
  void deletesEmptyStackAndReturnsPulumiShape() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "dev", fixtureTags());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/dev?force=false")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(
                  0L, count("SELECT count(*) FROM workspaces WHERE id = ?1", ref.workspaceId()));
              assertEquals(0L, count("SELECT count(*) FROM stacks WHERE id = ?1", ref.stackId()));
              assertEquals(
                  0L, count("SELECT count(*) FROM stack_state WHERE stack_id = ?1", ref.stackId()));
            });
  }

  @Test
  void nonEmptyStackReturns409WithoutForce() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "busy", fixtureTags());
    setJournalSeq(ref.stackId(), 1L);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/busy?force=false")
        .then()
        .statusCode(409);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(
                  1L, count("SELECT count(*) FROM workspaces WHERE id = ?1", ref.workspaceId()));
              assertEquals(1L, count("SELECT count(*) FROM stacks WHERE id = ?1", ref.stackId()));
              assertEquals(
                  1L, count("SELECT count(*) FROM stack_state WHERE stack_id = ?1", ref.stackId()));
            });
  }

  @Test
  void forceDeletesNonEmptyStack() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "force", fixtureTags());
    setJournalSeq(ref.stackId(), 1L);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/force?force=true")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(
                  0L, count("SELECT count(*) FROM workspaces WHERE id = ?1", ref.workspaceId()));
              assertEquals(0L, count("SELECT count(*) FROM stacks WHERE id = ?1", ref.stackId()));
              assertEquals(
                  0L, count("SELECT count(*) FROM stack_state WHERE stack_id = ?1", ref.stackId()));
            });
  }

  @Test
  void missingStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/missing?force=false")
        .then()
        .statusCode(404);
  }

  @Test
  void crossOrgCollapsesTo404() {
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

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .delete("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/secret-proj/secret?force=false")
        .then()
        .statusCode(404);
  }

  private void setJournalSeq(UUID stackId, long journalSeq) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("UPDATE stack_state SET journal_seq = ?1 WHERE stack_id = ?2")
                    .setParameter(1, journalSeq)
                    .setParameter(2, stackId)
                    .executeUpdate());
  }

  private long count(String sql, UUID id) {
    Number rows = (Number) em.createNativeQuery(sql).setParameter(1, id).getSingleResult();
    return rows.longValue();
  }

  private static Map<String, String> fixtureTags() {
    return Map.of(
        "pulumi:description",
        "Minimal Pulumi project for API recording - no resources, no providers.",
        "pulumi:project",
        PROJECT,
        "pulumi:runtime",
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
