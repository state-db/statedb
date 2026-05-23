package io.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.lock.LeaseTokenHasher;
import io.statedb.persistence.stack.StackCreator;
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

/** Covers both the cancel happy path and every failure mode the {@code LeaseTokenFilter} owns. */
@QuarkusTest
class LeaseTokenFilterTest {

  private static final String ORG = "lease-org";
  private static final String OTHER_STACK = "other";
  private static final String RAW_TOKEN = "lease-token-flow";
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
              org.displayName = "Lease Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "lease";
              user.email = "lease@example.com";
              user.displayName = "Lease Tester";
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
              t.description = "lease";
              t.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
              stackCreator.create(org.id, ORG, PROJECT, OTHER_STACK, Map.of());
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
                  .setParameter(2, "lease")
                  .executeUpdate();
            });
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
        .statusCode(409);
  }

  @Test
  void expiredLeaseReturns409() {
    String updateId = startPreview(STACK);
    String token = acquireLock(STACK, updateId);

    QuarkusTransaction.requiringNew()
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
