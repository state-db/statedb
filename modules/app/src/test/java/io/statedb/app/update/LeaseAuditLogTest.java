package io.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.StackCreator;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
class LeaseAuditLogTest {

  private static final String ORG = "audit-org";
  private static final String RAW_TOKEN = "audit-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  @Inject EntityManager em;
  @Inject StackCreator stackCreator;

  private ByteArrayOutputStream captured;
  private Handler handler;
  private Logger julLogger;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = "Audit Test";
              org.persistAndFlush();
              User user = new User();
              user.iss = "local:test";
              user.sub = "audit";
              user.email = "audit@example.com";
              user.displayName = "Audit Tester";
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
              t.description = "audit";
              t.persistAndFlush();
              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
            });

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
  void cleanup() {
    if (julLogger != null && handler != null) {
      julLogger.removeHandler(handler);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "audit")
                  .executeUpdate();
            });
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

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
