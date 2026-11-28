package app.statedb.app.update;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.journal.JournalEntry;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ForcedCancelContractTest extends DatabaseTest {

  private static final String ORG = "forced-cancel-org";
  private static final String RAW_TOKEN = "forced-cancel-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  private UUID stackId;

  @BeforeEach
  void seed() {
    stackId = db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK).stack().stackId();
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
    return requiringNew()
        .call(() -> StackUpdate.<StackUpdate>findById(UUID.fromString(updateId)).status);
  }

  private UUID activeUpdateId() {
    return requiringNew().call(() -> Stack.<Stack>findById(stackId).activeUpdateId);
  }

  private long lockCount() {
    return StackLock.count("stackId", stackId);
  }

  private long journalEntryCount(String updateId) {
    return JournalEntry.count("stackId = ?1 and updateId = ?2", stackId, UUID.fromString(updateId));
  }

  private long updateEventCount(String updateId) {
    return UpdateEvent.count("stackId = ?1 and updateId = ?2", stackId, UUID.fromString(updateId));
  }
}
