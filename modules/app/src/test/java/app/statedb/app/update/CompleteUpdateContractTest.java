package app.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import app.statedb.app.contract.PulumiContractIdentity;
import app.statedb.persistence.stack.StackCreator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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

  private static final PulumiContractIdentity IDENTITY =
      new PulumiContractIdentity(
          ORG,
          "Complete Test",
          "complete",
          "complete@example.com",
          "Complete Tester",
          RAW_TOKEN,
          "complete");

  @BeforeEach
  void seed() {
    IDENTITY.seedStack(stackCreator, PROJECT, STACK);
  }

  @AfterEach
  void cleanup() {
    IDENTITY.cleanup();
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
        .statusCode(409)
        .body("code", equalTo(409))
        .body("message", equalTo("update lease is no longer valid"));
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
}
