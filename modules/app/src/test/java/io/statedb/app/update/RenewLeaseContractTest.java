package io.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RenewLeaseContractTest {

  private static final String ORG = "renew-org";
  private static final String RAW_TOKEN = "renew-token";
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
              org.displayName = "Renew Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "renew";
              user.email = "renew@example.com";
              user.displayName = "Renew Tester";
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
              t.description = "renew";
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
                  .setParameter(2, "renew")
                  .executeUpdate();
            });
  }

  @Test
  void renewHappyPathReturns200AndAdvancesExpiry() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    // Back-date so the advance is observable without sub-second clock games.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks SET expires_at = now() + interval '5 minutes'"
                            + " WHERE lock_token_hash = ?1")
                    .setParameter(1, LeaseTokenHasher.hash(UUID.fromString(token)))
                    .executeUpdate());
    Instant before = readExpiresAt(token);

    var response =
        given()
            .header("Authorization", "update-token " + token)
            .contentType("application/json")
            .body(Map.of("duration", 3600))
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
                    + "/renew_lease")
            .then()
            .statusCode(200)
            .body("token", not(equalTo(token)))
            .body("tokenExpiration", greaterThan(0))
            .extract();
    String rotated = response.path("token");
    long tokenExpiration = ((Number) response.path("tokenExpiration")).longValue();

    assertNotEquals(token, rotated, "renew must rotate the token");
    Instant after = readExpiresAt(rotated);
    assertTrue(after.isAfter(before), "expires_at should advance");
    assertTrue(tokenExpiration >= after.getEpochSecond() - 2, "response should pin to db row");
  }

  @Test
  void renewWithUnknownTokenReturns409() {
    String updateId = startPreview();
    acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + UUID.randomUUID())
        .contentType("application/json")
        .body(Map.of("duration", 3600))
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
                + "/renew_lease")
        .then()
        .statusCode(409);
  }

  @Test
  void renewWithMismatchedBodyTokenReturns409() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    given()
        .header("Authorization", "update-token " + token)
        .contentType("application/json")
        .body(Map.of("duration", 3600, "token", UUID.randomUUID().toString()))
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
                + "/renew_lease")
        .then()
        .statusCode(409);
  }

  @Test
  void renewAfterCancelReturns409() {
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
        .body(Map.of("duration", 3600))
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
                + "/renew_lease")
        .then()
        .statusCode(409);
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

  private Instant readExpiresAt(String token) {
    Object raw =
        em.createNativeQuery("SELECT expires_at FROM stack_locks WHERE lock_token_hash = ?1")
            .setParameter(1, LeaseTokenHasher.hash(UUID.fromString(token)))
            .getSingleResult();
    if (raw instanceof Instant i) return i;
    if (raw instanceof java.time.OffsetDateTime o) return o.toInstant();
    if (raw instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + raw.getClass());
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
