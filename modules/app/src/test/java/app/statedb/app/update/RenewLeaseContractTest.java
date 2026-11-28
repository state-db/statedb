package app.statedb.app.update;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.lock.LeaseTokenHasher;
import app.statedb.persistence.lock.LockConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RenewLeaseContractTest extends DatabaseTest {

  private static final String ORG = "renew-org";
  private static final String RAW_TOKEN = "renew-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  @Inject LockConfig lockConfig;

  @BeforeEach
  void seed() {
    db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK);
  }

  @Test
  void renewHappyPathReturns200AndAdvancesExpiry() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    // Back-date so the advance is observable without sub-second clock games.
    requiringNew()
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
  void renewHonorsTheCliRequestedDuration() {
    String updateId = startPreview();
    long now = Instant.now().getEpochSecond();

    long expiration = renewExpiration(updateId, acquireLock(updateId), Map.of("duration", 120));

    assertTrue(expiration < now + 180, "a request below lease-ttl must not be widened to it");
  }

  @Test
  void renewWithMissingDurationFallsBackToLeaseTtl() {
    String updateId = startPreview();
    long now = Instant.now().getEpochSecond();

    long expiration = renewExpiration(updateId, acquireLock(updateId), Map.of());

    assertTrue(expiration >= now + lockConfig.leaseTtl().toSeconds() - 10);
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
        .statusCode(409)
        .body("code", equalTo(409))
        .body("message", equalTo("update lease is no longer valid"));
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

  private long renewExpiration(String updateId, String token, Map<String, ?> body) {
    Number expiration =
        given()
            .header("Authorization", "update-token " + token)
            .contentType("application/json")
            .body(body)
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
            .extract()
            .path("tokenExpiration");
    return expiration.longValue();
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
}
