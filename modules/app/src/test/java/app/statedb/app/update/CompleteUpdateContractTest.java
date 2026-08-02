package app.statedb.app.update;

import static io.restassured.RestAssured.given;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.StackCreator;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

@QuarkusTest
class CompleteUpdateContractTest {

  private static final String ORG = "complete-org";
  private static final String RAW_TOKEN = "complete-token";
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
              org.displayName = "Complete Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "complete";
              user.email = "complete@example.com";
              user.displayName = "Complete Tester";
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
              t.description = "complete";
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
                  .setParameter(2, "complete")
                  .executeUpdate();
            });
  }

  @Test
  void succeededReturns200AndClearsLock() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of("status", "succeeded"))
        .when()
        .post(completePath(updateId))
        .then()
        .statusCode(200);

    Number locks =
        (Number)
            em.createNativeQuery(
                    "SELECT count(*) FROM stack_locks l"
                        + " JOIN stacks s ON s.id = l.stack_id"
                        + " WHERE s.name = ?1")
                .setParameter(1, STACK)
                .getSingleResult();
    org.junit.jupiter.api.Assertions.assertEquals(0L, locks.longValue());
  }

  @Test
  void unknownStatusReturns400() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of("status", "partial"))
        .when()
        .post(completePath(updateId))
        .then()
        .statusCode(400);
  }

  @Test
  void missingStatusReturns400() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of())
        .when()
        .post(completePath(updateId))
        .then()
        .statusCode(400);
  }

  @Test
  void unknownLeaseReturns409() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + UUID.randomUUID())
        .contentType("application/json")
        .body(Map.of("status", "succeeded"))
        .when()
        .post(completePath(updateId))
        .then()
        .statusCode(409);
  }

  @Test
  void completeAfterCancelReturns409() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(204);

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of("status", "succeeded"))
        .when()
        .post(completePath(updateId))
        .then()
        .statusCode(409);
  }

  private String completePath(String updateId) {
    return "/pulumi/api/stacks/"
        + ORG
        + "/"
        + PROJECT
        + "/"
        + STACK
        + "/update/"
        + updateId
        + "/complete";
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
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(200)
        .extract()
        .path("token");
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
