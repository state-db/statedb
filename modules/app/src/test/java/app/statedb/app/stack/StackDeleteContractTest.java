package app.statedb.app.stack;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Snapshot;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
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

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ORG_NAME = "stack-delete-org";
  private static final String OTHER_ORG_NAME = "stack-delete-other-org";
  private static final String RAW_TOKEN = "stack-delete-token";
  private static final String PROJECT = "recorder-test";

  @Inject EntityManager em;

  @Inject StackCreator stackCreator;

  @Inject StackLockAcquirer stackLockAcquirer;

  private UUID orgId;

  @BeforeEach
  void seed() {
    orgId =
        read(
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
              t.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
              t.description = "stack-delete";
              t.persistAndFlush();

              return org.id;
            });
  }

  @AfterEach
  void cleanup() {
    tx(
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

    assertStackRows(0L, ref);
  }

  @Test
  void nonEmptyStackReturns409WithoutForce() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "busy", fixtureTags());
    setBaseSnapshot(
        ref.stackId(),
        1L,
        "[{\"urn\":\"urn:pulumi:dev::recorder-test::pkg:index:Thing::thing\","
            + "\"type\":\"pkg:index:Thing\",\"custom\":true}]");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/busy?force=false")
        .then()
        .statusCode(409);

    assertStackRows(1L, ref);
  }

  @Test
  void deletesStackWithOnlyRootStackResourceAndJournalHistory() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "destroyed", fixtureTags());
    setBaseSnapshot(
        ref.stackId(),
        42L,
        "[{\"urn\":\"urn:pulumi:dev::recorder-test::pulumi:pulumi:Stack::recorder-test-dev\","
            + "\"type\":\"pulumi:pulumi:Stack\",\"custom\":false,\"outputs\":{}}]");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/destroyed?force=false")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackRows(0L, ref);
  }

  @Test
  void forceDeletesNonEmptyStack() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "force", fixtureTags());
    setBaseSnapshot(
        ref.stackId(),
        1L,
        "[{\"urn\":\"urn:pulumi:dev::recorder-test::pkg:index:Thing::thing\","
            + "\"type\":\"pkg:index:Thing\",\"custom\":true}]");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/force?force=true")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackRows(0L, ref);
  }

  @Test
  void rejectsDeleteWhileUpdateIsActive() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "active", fixtureTags());
    UUID updateId = seedActiveUpdate(ref.stackId());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/active?force=false")
        .then()
        .statusCode(409);

    assertStackAndUpdateRows(1L, ref, updateId);
  }

  @Test
  void forceDeletesStackWithActiveUpdate() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, PROJECT, "active-force", fixtureTags());
    UUID updateId = seedActiveUpdate(ref.stackId());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .contentType("application/json")
        .when()
        .delete("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/active-force?force=true")
        .then()
        .statusCode(200)
        .body("$", anEmptyMap());

    assertStackAndUpdateRows(0L, ref, updateId);
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
    seedOtherOrganizationStack();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .delete("/pulumi/api/stacks/" + OTHER_ORG_NAME + "/secret-proj/secret?force=false")
        .then()
        .statusCode(404);
  }

  private void setBaseSnapshot(UUID stackId, long journalSeq, String resourcesJson) {
    tx(
        () -> {
          Snapshot snapshot = new Snapshot();
          snapshot.stackId = stackId;
          snapshot.version = 1;
          snapshot.checkpoint = deployment(resourcesJson);
          snapshot.compactedFromJournalSeq = journalSeq;
          snapshot.persistAndFlush();

          StackState state = StackState.find("stackId", stackId).firstResult();
          state.baseSnapshotId = snapshot.id;
          state.baseSnapshotVersion = snapshot.version;
          state.journalSeq = journalSeq;
          state.persistAndFlush();
        });
  }

  private UUID seedActiveUpdate(UUID stackId) {
    UUID updateId =
        read(
            () -> {
              StackUpdate update = new StackUpdate();
              update.stackId = stackId;
              update.version = 1;
              update.kind = StackUpdate.Kind.UPDATE;
              update.status = StackUpdate.Status.IN_PROGRESS;
              update.startedAt = java.time.Instant.now();
              update.persistAndFlush();
              return update.id;
            });
    stackLockAcquirer
        .acquire(stackId, updateId, "stack-delete-test", null, null)
        .orElseThrow(() -> new AssertionError("expected active update lease"));
    return updateId;
  }

  private static JsonNode deployment(String resourcesJson) {
    try {
      return MAPPER.readTree("{\"resources\":" + resourcesJson + "}");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
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

  private void seedOtherOrganizationStack() {
    tx(
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
  }

  private void assertStackRows(long expected, StackRef ref) {
    tx(() -> checkStackRows(expected, ref));
  }

  private void assertStackAndUpdateRows(long expected, StackRef ref, UUID updateId) {
    tx(
        () -> {
          checkStackRows(expected, ref);
          assertEquals(
              expected, count("SELECT count(*) FROM stack_updates WHERE id = ?1", updateId));
          assertEquals(
              expected,
              count("SELECT count(*) FROM stack_locks WHERE stack_id = ?1", ref.stackId()));
        });
  }

  private void checkStackRows(long expected, StackRef ref) {
    assertEquals(
        expected, count("SELECT count(*) FROM workspaces WHERE id = ?1", ref.workspaceId()));
    assertEquals(expected, count("SELECT count(*) FROM stacks WHERE id = ?1", ref.stackId()));
    assertEquals(
        expected, count("SELECT count(*) FROM stack_state WHERE stack_id = ?1", ref.stackId()));
  }
}
