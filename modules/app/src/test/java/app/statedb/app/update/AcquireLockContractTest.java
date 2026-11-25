package app.statedb.app.update;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.StackCreator;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of {@code POST .../update/{updateID}} against the recorded fixture at {@code
 * tools/pulumi/recorder/fixtures/by-command/03-preview/0006_…}. Cannot replay the fixture path
 * literally because the recorder's stub returned a {@code preview-0001} update-ID; the real server
 * returns a UUID, so we mint the update via the start-update endpoint first and substitute.
 */
@QuarkusTest
class AcquireLockContractTest {

  private static final String ORG = "acquire-org";
  private static final String RAW_TOKEN = "acquire-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";

  @Inject EntityManager em;
  @Inject StackCreator stackCreator;

  @BeforeEach
  void seed() {
    tx(
        () -> {
          Organization org = new Organization();
          org.name = ORG;
          org.displayName = "Acquire Test";
          org.persistAndFlush();

          User user = new User();
          user.iss = "local:test";
          user.sub = "acquire";
          user.email = "acquire@example.com";
          user.displayName = "Acquire Tester";
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
          t.description = "acquire";
          t.persistAndFlush();

          stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
        });
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
              .setParameter(1, ORG)
              .executeUpdate();
          em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
              .setParameter(1, "local:test")
              .setParameter(2, "acquire")
              .executeUpdate();
        });
  }

  @Test
  void acquireReturnsPulumiLeaseContractAndPersistsAcquireBody() {
    String updateId = startPreview();

    ValidatableResponse response =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .contentType("application/json")
            .body(Map.of("journalVersion", 1, "tags", Map.of("pulumi:runtime", "yaml")))
            .when()
            .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
            .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("tokenExpiration", greaterThan(0))
            // Previews are dry runs and never consume the applied-version sequence.
            .body("version", equalTo(0))
            .body("journalVersion", equalTo(1))
            .body("duration", nullValue());

    long tokenExpiration = ((Number) response.extract().path("tokenExpiration")).longValue();

    tx(
        () -> {
          Object[] row =
              (Object[])
                  em.createNativeQuery(
                          """
                              SELECT su.journal_version, su.tags, sl.expires_at
                                FROM stack_updates su
                                JOIN stack_locks sl ON sl.stack_id = su.stack_id
                               WHERE su.id = ?1
                              """)
                      .setParameter(1, UUID.fromString(updateId))
                      .getSingleResult();
          assertEquals(1, ((Number) row[0]).intValue());
          assertNotNull(row[1]);
          assertEquals(toInstant(row[2]).getEpochSecond(), tokenExpiration);

          Number lockCount =
              (Number)
                  em.createNativeQuery(
                          "SELECT count(*) FROM stack_locks WHERE stack_id"
                              + " = (SELECT stack_id FROM stack_updates WHERE id = ?1)")
                      .setParameter(1, UUID.fromString(updateId))
                      .getSingleResult();
          assertEquals(1L, lockCount.longValue());
        });
  }

  @Test
  void concurrentAcquireReturns409() {
    String firstUpdate = startPreview();
    String secondUpdate = startPreview();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + firstUpdate)
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + secondUpdate)
        .then()
        .statusCode(409)
        .contentType(JSON)
        .body("code", equalTo(409))
        .body("message", containsString("is currently locked by another update"));
  }

  @Test
  void acquireReturnsPersistedUpdateVersion() {
    startUpdate();
    startUpdate();
    String thirdUpdate = startUpdate();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + thirdUpdate)
        .then()
        .statusCode(200)
        .body("version", equalTo(3));
  }

  @Test
  void acquireRejectsProjectTagMismatchInsteadOfUsingPathContext() {
    String updateId = startPreview();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1, "tags", Map.of("pulumi:project", "other-project")))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(400)
        .body("message", containsString("tags.pulumi:project must match the stack path"));
  }

  private String startPreview() {
    return startOp("preview");
  }

  private String startUpdate() {
    return startOp("update");
  }

  private String startOp(String op) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", PROJECT, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/" + op)
        .then()
        .statusCode(200)
        .extract()
        .path("updateID");
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

  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
