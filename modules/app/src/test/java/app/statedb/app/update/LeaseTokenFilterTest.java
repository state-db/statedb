package app.statedb.app.update;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.lock.LeaseTokenHasher;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers both the cancel happy path and every failure mode the {@code LeaseTokenFilter} owns. */
@QuarkusTest
class LeaseTokenFilterTest extends DatabaseTest {

  private static final String ORG = "lease-org";
  private static final String OTHER_STACK = "other";
  private static final String RAW_TOKEN = "lease-token-flow";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  @BeforeEach
  void seed() {
    db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK);
    db.seedStack(ORG, PROJECT, OTHER_STACK);
  }

  @Test
  void missingAuthorizationHeaderReturns401() {
    String updateId = startPreview(STACK);
    acquireLock(STACK, updateId);

    given()
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(401);
  }

  @Test
  void wrongAuthSchemeReturns401() {
    String updateId = startPreview(STACK);
    acquireLock(STACK, updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(401);
  }

  @Test
  void malformedUpdateTokenReturns401() {
    String updateId = startPreview(STACK);
    acquireLock(STACK, updateId);

    given()
        .header("Authorization", "update-token not-a-uuid")
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(401);
  }

  @Test
  void unknownLeaseTokenReturns409() {
    String updateId = startPreview(STACK);
    acquireLock(STACK, updateId);

    given()
        .header("Authorization", "update-token " + UUID.randomUUID())
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(409)
        .body("code", equalTo(409))
        .body("message", equalTo("update lease is no longer valid"));
  }

  @Test
  void expiredLeaseReturns409() {
    String updateId = startPreview(STACK);
    String token = acquireLock(STACK, updateId);

    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks SET expires_at = now() - interval '1 second'"
                            + " WHERE lock_token_hash = ?1")
                    .setParameter(1, LeaseTokenHasher.hash(UUID.fromString(token)))
                    .executeUpdate());

    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(409);
  }

  @Test
  void pathStackMismatchReturns409() {
    String updateId = startPreview(STACK);
    String token = acquireLock(STACK, updateId);

    // Same updateID, but a different stack path. Filter resolves the path's stack and refuses.
    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete(
            "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + OTHER_STACK + "/update/" + updateId)
        .then()
        .statusCode(409);
  }

  @Test
  void pathUpdateIdMismatchReturns409() {
    String updateId = startPreview(STACK);
    String token = acquireLock(STACK, updateId);

    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete(
            "/pulumi/api/stacks/"
                + ORG
                + "/"
                + PROJECT
                + "/"
                + STACK
                + "/update/"
                + UUID.randomUUID())
        .then()
        .statusCode(409);
  }

  @Test
  void malformedPathUpdateIdReturns409() {
    String updateId = startPreview(STACK);
    String token = acquireLock(STACK, updateId);

    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/not-a-uuid")
        .then()
        .statusCode(409)
        .body("code", equalTo(409))
        .body("message", equalTo("update lease is no longer valid"));
  }

  @Test
  void cancelHappyPathReturns204AndRemovesLock() {
    String updateId = startPreview(STACK);
    String token = acquireLock(STACK, updateId);

    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(204);

    Number lockCount =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE lock_token_hash = ?1")
                .setParameter(1, LeaseTokenHasher.hash(UUID.fromString(token)))
                .getSingleResult();
    assertEquals(0L, lockCount.longValue());

    String status =
        (String)
            em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
                .setParameter(1, UUID.fromString(updateId))
                .getSingleResult();
    assertEquals("cancelled", status);
  }

  private String startPreview(String stackName) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", PROJECT, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + stackName + "/preview")
        .then()
        .statusCode(200)
        .extract()
        .path("updateID");
  }

  private String acquireLock(String stackName, String updateId) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + stackName + "/update/" + updateId)
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }
}
