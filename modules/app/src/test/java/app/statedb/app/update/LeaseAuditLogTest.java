package app.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import io.quarkus.test.junit.QuarkusTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lease-audit lines are emitted via JBoss Logging, which Quarkus routes through java.util.logging.
 * We attach a capturing handler to the {@code lease-audit} category and assert the structured
 * fields appear on a real cancel.
 */
@QuarkusTest
class LeaseAuditLogTest extends DatabaseTest {

  private static final String ORG = "audit-org";
  private static final String RAW_TOKEN = "audit-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  private ByteArrayOutputStream captured;
  private Handler handler;
  private Logger julLogger;

  @BeforeEach
  void seed() {
    db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK);

    captured = new ByteArrayOutputStream();
    handler =
        new ConsoleHandler() {
          {
            setOutputStream(new PrintStream(captured, true, StandardCharsets.UTF_8));
          }
        };
    julLogger = LogManager.getLogManager().getLogger("lease-audit");
    if (julLogger == null) {
      julLogger = Logger.getLogger("lease-audit");
    }
    julLogger.addHandler(handler);
  }

  @AfterEach
  void detachHandler() {
    if (julLogger != null && handler != null) {
      julLogger.removeHandler(handler);
    }
  }

  @Test
  void cancelEmitsAuditLineWithLeaseContext() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + token)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(204);

    handler.flush();
    String log = captured.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("lease_audit"), () -> "expected audit line, got: " + log);
    assertTrue(log.contains("status=204"), () -> "expected 204 outcome, got: " + log);
    assertTrue(log.contains("update_id=" + updateId), () -> "expected update_id, got: " + log);
    assertTrue(
        log.contains("action=graceful-cancel"), () -> "expected graceful cancel, got: " + log);
  }

  @Test
  void forcedCancelEmitsDistinctAuditLine() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .post(
            "/pulumi/api/stacks/"
                + ORG
                + "/"
                + PROJECT
                + "/"
                + STACK
                + "/update/"
                + updateId
                + "/cancel")
        .then()
        .statusCode(204);

    handler.flush();
    String log = captured.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("lease_audit"), () -> "expected audit line, got: " + log);
    assertTrue(log.contains("status=204"), () -> "expected 204 outcome, got: " + log);
    assertTrue(log.contains("update_id=" + updateId), () -> "expected update_id, got: " + log);
    assertTrue(log.contains("action=forced-cancel"), () -> "expected forced cancel, got: " + log);
  }

  @Test
  void rejectedForcedCancelEmitsRejectedOutcome() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .when()
        .post(
            "/pulumi/api/stacks/"
                + ORG
                + "/"
                + PROJECT
                + "/"
                + STACK
                + "/update/"
                + updateId
                + "/cancel")
        .then()
        .statusCode(401);

    handler.flush();
    String log = captured.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("action=forced-cancel"), () -> "expected forced cancel, got: " + log);
    assertTrue(log.contains("outcome=rejected"), () -> "expected rejected outcome, got: " + log);
    assertTrue(log.contains("status=401"), () -> "expected 401, got: " + log);
    assertTrue(log.contains("update_id=" + updateId), () -> "expected update_id, got: " + log);
  }

  @Test
  void rejectedLeaseEmitsAuditLineWithoutContext() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + UUID.randomUUID())
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(409);

    handler.flush();
    String log = captured.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("outcome=rejected"), () -> "expected rejected outcome, got: " + log);
    assertTrue(log.contains("status=409"), () -> "expected 409, got: " + log);
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
