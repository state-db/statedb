package io.statedb.app.update;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
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
    QuarkusTransaction.requiringNew()
        .run(
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
              t.description = "acquire";
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
                  .setParameter(2, "acquire")
                  .executeUpdate();
            });
  }

  @Test
  void acquireReturnsTokenAndDurationAndPersistsAcquireBody() {
    String updateId = startPreview();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1, "tags", Map.of("pulumi:runtime", "yaml")))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(200)
        .body("token", notNullValue())
        .body("duration", equalTo(3600));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Object[] row =
                  (Object[])
                      em.createNativeQuery(
                              "SELECT journal_version, tags FROM stack_updates WHERE id = ?1")
                          .setParameter(1, UUID.fromString(updateId))
                          .getSingleResult();
              assertEquals(1, ((Number) row[0]).intValue());
              assertNotNull(row[1]);

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
