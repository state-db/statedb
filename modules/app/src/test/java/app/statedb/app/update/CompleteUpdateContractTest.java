package app.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.lock.StackLock;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CompleteUpdateContractTest extends DatabaseTest {

  private static final String ORG = "complete-org";
  private static final String RAW_TOKEN = "complete-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  private UUID stackId;

  @BeforeEach
  void seed() {
    stackId = db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK).stack().stackId();
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

    assertEquals(0L, StackLock.count("stackId", stackId));
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
