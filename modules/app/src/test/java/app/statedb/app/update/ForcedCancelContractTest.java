package app.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ForcedCancelContractTest {

  private static final String ORG = "forced-cancel-org";
  private static final String RAW_TOKEN = "forced-cancel-token";
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
              org.name = ORG;
              org.displayName = "Forced Cancel Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "forced-cancel";
              user.email = "forced-cancel@example.com";
              user.displayName = "Forced Cancel Tester";
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
              t.description = "forced cancel";
              t.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "forced-cancel")
                  .executeUpdate();
            });
  }

  @Test
  void forcedCancelByNonLeaseHolderReleasesLockAndMarksUpdateCancelled() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .post(cancelPath(updateId))
        .then()
        .statusCode(204);

    assertEquals(0L, lockCount());
    assertEquals(StackUpdate.Status.CANCELLED, updateStatus(updateId));
    assertEquals(null, activeUpdateId());
  }

  @Test
  void journalAppendOnRevokedLeaseIsRejectedAndWritesNothing() {
    String updateId = startPreview();
    String leaseToken = acquireLock(updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .post(cancelPath(updateId))
        .then()
        .statusCode(204);

    given()
        .header("Authorization", "update-token " + leaseToken)
        .contentType("application/json")
        .body(
            Map.of(
                "entries",
                List.of(Map.of("kind", 6, "operationID", 0, "sequenceID", 1, "version", 1))))
        .when()
        .patch(updatePath(updateId) + "/journalentries")
        .then()
        .statusCode(409);

    assertEquals(0L, journalEntryCount(updateId));
  }

  @Test
  void revokedLeaseCannotMutateEventsOrComplete() {
    String updateId = startPreview();
    String leaseToken = acquireLock(updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .post(cancelPath(updateId))
        .then()
        .statusCode(204);

    given()
        .header("Authorization", "update-token " + leaseToken)
        .contentType("application/json")
        .body(Map.of("events", List.of(Map.of("sequence", 1, "cancelEvent", Map.of()))))
        .when()
        .post(updatePath(updateId) + "/events/batch")
        .then()
        .statusCode(409);

    given()
        .header("Authorization", "update-token " + leaseToken)
        .contentType("application/json")
        .body(Map.of("status", "succeeded"))
        .when()
        .post(updatePath(updateId) + "/complete")
        .then()
        .statusCode(409);

    assertEquals(0L, updateEventCount(updateId));
    assertEquals(StackUpdate.Status.CANCELLED, updateStatus(updateId));
  }

  @Test
  void forcedCancelRequiresAuthorization() {
    String updateId = startPreview();
    acquireLock(updateId);

    given().when().post(cancelPath(updateId)).then().statusCode(401);

    assertEquals(1L, lockCount());
    assertEquals(StackUpdate.Status.IN_PROGRESS, updateStatus(updateId));
  }

  @Test
  void forcedCancelAgainstAlreadyTerminalUpdateIsIdempotent() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .post(cancelPath(updateId))
        .then()
        .statusCode(204);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .post(cancelPath(updateId))
        .then()
        .statusCode(204);

    assertEquals(0L, lockCount());
    assertEquals(StackUpdate.Status.CANCELLED, updateStatus(updateId));
  }

  @Test
  void forcedCancelWithUnknownUpdateReportsUpdateNotFound() {
    String unknownUpdateId = UUID.randomUUID().toString();

    var response =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .when()
            .post(cancelPath(unknownUpdateId))
            .then()
            .statusCode(404)
            .extract();

    assertEquals("update not found", response.path("message"));
    assertEquals(unknownUpdateId, response.path("detail"));
  }

  private String startPreview() {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", PROJECT, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(200)
        .extract()
        .path("updateID");
  }

  private String acquireLock(String updateId) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post(updatePath(updateId))
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }

  private String cancelPath(String updateId) {
    return updatePath(updateId) + "/cancel";
  }

  private String updatePath(String updateId) {
    return "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId;
  }

  private StackUpdate.Status updateStatus(String updateId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> StackUpdate.<StackUpdate>findById(UUID.fromString(updateId)).status);
  }

  private UUID activeUpdateId() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                Stack.<Stack>find(
                        "project.organization.name = ?1 and project.name = ?2 and name = ?3",
                        ORG,
                        PROJECT,
                        STACK)
                    .singleResult()
                    .activeUpdateId);
  }

  private long lockCount() {
    return QuarkusTransaction.requiringNew().call(() -> StackLock.count("stackId", stackId()));
  }

  private long journalEntryCount(String updateId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                JournalEntry.count(
                    "stackId = ?1 and updateId = ?2", stackId(), UUID.fromString(updateId)));
  }

  private long updateEventCount(String updateId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                UpdateEvent.count(
                    "stackId = ?1 and updateId = ?2", stackId(), UUID.fromString(updateId)));
  }

  private UUID stackId() {
    return Stack.<Stack>find(
            "project.organization.name = ?1 and project.name = ?2 and name = ?3",
            ORG,
            PROJECT,
            STACK)
        .singleResult()
        .id;
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
