package app.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.Stack;
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
 * Pins {@code GET /api/stacks/{org}/{project}/{stack}/updates} — the endpoint behind {@code pulumi
 * stack history}. Returns the legacy {@code {"updates":[...], "itemsPerPage": N}} shape
 * newest-first, excluding previews (not real deployments) and renames (audit-only, never shown by
 * stock Pulumi).
 */
@QuarkusTest
class StackHistoryContractTest {

  private static final String ORG_NAME = "stack-history-org";
  private static final String RAW_TOKEN = "stack-history-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT;
  private static final String UPDATES = BASE + "/" + STACK + "/updates";

  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Stack History Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "stack-history";
              user.email = "history@example.com";
              user.displayName = "Stack History Tester";
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
              t.description = "stack-history";
              t.persistAndFlush();
            });

    createStack(STACK);
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG_NAME)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "stack-history")
                  .executeUpdate();
            });
  }

  @Test
  void emptyStackReturnsNoUpdates() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES + "?pageSize=10&page=1")
        .then()
        .statusCode(200)
        .body("updates", hasSize(0))
        .body("itemsPerPage", equalTo(10));
  }

  @Test
  void returnsCompletedUpdate() {
    Instant start = Instant.ofEpochSecond(1_700_000_000L);
    Instant end = Instant.ofEpochSecond(1_700_000_042L);
    addUpdate(StackUpdate.Kind.UPDATE, StackUpdate.Status.SUCCEEDED, 1, start, end, "first up");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES)
        .then()
        .statusCode(200)
        .body("updates", hasSize(1))
        .body("updates[0].kind", equalTo("update"))
        .body("updates[0].result", equalTo("succeeded"))
        .body("updates[0].version", equalTo(1))
        .body("updates[0].message", equalTo("first up"))
        .body("updates[0].startTime", equalTo(1_700_000_000))
        .body("updates[0].endTime", equalTo(1_700_000_042))
        .body("updates[0].environment", equalTo(Map.of()))
        .body("updates[0].config", equalTo(Map.of()));
  }

  @Test
  void updateWithoutMessageStillCarriesTheKey() {
    // Import updates are recorded without a message. apitype.UpdateInfo declares Message as a
    // non-pointer string, so the key must still appear as "" rather than being dropped.
    Instant t = Instant.ofEpochSecond(1_700_000_000L);
    addUpdate(StackUpdate.Kind.IMPORT, StackUpdate.Status.SUCCEEDED, 1, t, t, null);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES)
        .then()
        .statusCode(200)
        .body("updates[0].kind", equalTo("import"))
        .body("updates[0]", hasKey("message"))
        .body("updates[0].message", equalTo(""));
  }

  @Test
  void surfacesPersistedConfigAndEnvironment() {
    // Pulumi's UpdateProgramRequest carries config as map[string]ConfigValue and the run
    // environment
    // under metadata.environment; both must round-trip into stack history --json verbatim.
    Instant t = Instant.ofEpochSecond(1_700_000_000L);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackUpdate u = new StackUpdate();
              u.stackId = stackId();
              u.version = 1;
              u.kind = StackUpdate.Kind.UPDATE;
              u.status = StackUpdate.Status.SUCCEEDED;
              u.startedAt = t;
              u.endedAt = t;
              u.config = Map.of("aws:region", Map.of("string", "us-east-1", "secret", false));
              u.metadata =
                  Map.of(
                      "message",
                      "deploy",
                      "environment",
                      Map.of("pulumi.version", "3.100.0", "ci", "false"));
              u.persistAndFlush();
            });

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES)
        .then()
        .statusCode(200)
        .body("updates[0].config.\"aws:region\".string", equalTo("us-east-1"))
        .body("updates[0].config.\"aws:region\".secret", equalTo(false))
        .body("updates[0].environment.\"pulumi.version\"", equalTo("3.100.0"))
        .body("updates[0].environment.ci", equalTo("false"));
  }

  @Test
  void newestFirstAndExcludesPreviewsAndRenames() {
    Instant t = Instant.ofEpochSecond(1_700_000_000L);
    addUpdate(StackUpdate.Kind.UPDATE, StackUpdate.Status.SUCCEEDED, 1, t, t, "v1");
    addUpdate(StackUpdate.Kind.PREVIEW, StackUpdate.Status.SUCCEEDED, 2, t, t, "preview");
    addUpdate(StackUpdate.Kind.RENAME, StackUpdate.Status.SUCCEEDED, 3, t, t, "rename");
    addUpdate(StackUpdate.Kind.DESTROY, StackUpdate.Status.SUCCEEDED, 4, t, t, "v4");
    // The dry-run pass of a `pulumi up` shares the apply's kind but never surfaces in history.
    addDryRunUpdate(t);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES)
        .then()
        .statusCode(200)
        .body("updates", hasSize(2))
        .body("updates[0].kind", equalTo("destroy"))
        .body("updates[0].version", equalTo(4))
        .body("updates[1].kind", equalTo("update"))
        .body("updates[1].version", equalTo(1));
  }

  @Test
  void inProgressUpdateOmitsEndTimeAsZero() {
    Instant start = Instant.ofEpochSecond(1_700_000_000L);
    addUpdate(StackUpdate.Kind.UPDATE, StackUpdate.Status.IN_PROGRESS, 1, start, null, null);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES)
        .then()
        .statusCode(200)
        .body("updates[0].result", equalTo("in-progress"))
        .body("updates[0].endTime", equalTo(0));
  }

  @Test
  void nonPositivePageSizeReturns400() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES + "?page=1&pageSize=0")
        .then()
        .statusCode(400);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES + "?page=1&pageSize=-1")
        .then()
        .statusCode(400);
  }

  @Test
  void oversizedPageSizeIsClampedNotPassedToDb() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(UPDATES + "?page=1&pageSize=5000")
        .then()
        .statusCode(200)
        .body("itemsPerPage", equalTo(100));
  }

  @Test
  void missingStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(BASE + "/does-not-exist/updates")
        .then()
        .statusCode(404);
  }

  @Test
  void missingTokenReturns401() {
    given().when().get(UPDATES).then().statusCode(401);
  }

  private void addUpdate(
      StackUpdate.Kind kind,
      StackUpdate.Status status,
      int version,
      Instant startedAt,
      Instant endedAt,
      String message) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackUpdate u = new StackUpdate();
              u.stackId = stackId();
              u.version = version;
              u.kind = kind;
              u.status = status;
              u.startedAt = startedAt;
              u.endedAt = endedAt;
              u.message = message;
              u.persistAndFlush();
            });
  }

  private void addDryRunUpdate(Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackUpdate u = new StackUpdate();
              u.stackId = stackId();
              u.version = 0;
              u.dryRun = true;
              u.kind = StackUpdate.Kind.UPDATE;
              u.status = StackUpdate.Status.SUCCEEDED;
              u.startedAt = at;
              u.endedAt = at;
              u.message = "dry-run pass";
              u.persistAndFlush();
            });
  }

  // Must be called inside an active transaction (the entity hydration needs a session).
  private static UUID stackId() {
    return Stack.<Stack>find(
            "select s from Stack s join s.project p join p.organization o"
                + " where o.name = ?1 and p.name = ?2 and s.name = ?3",
            ORG_NAME,
            PROJECT,
            STACK)
        .firstResult()
        .id;
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
