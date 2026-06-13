package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the deliberate 501 stubs for {@code pulumi stack schedule list} ({@code GET
 * .../deployments/schedules}) and {@code pulumi stack webhook list} ({@code GET .../hooks}):
 * state-db is a state backend, so these features are unsupported. The stubs carry no
 * {@code @Authorize}, so the test also confirms {@code TokenAuthFilter} still enforces 401 without
 * a token.
 */
@QuarkusTest
class UnsupportedFeatureContractTest {

  private static final String ORG_NAME = "unsupported-feature-org";
  private static final String RAW_TOKEN = "unsupported-feature-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + STACK;
  private static final String SCHEDULES = BASE + "/deployments/schedules";
  private static final String HOOKS = BASE + "/hooks";

  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Unsupported Feature Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "unsupported-feature";
              user.email = "unsupported@example.com";
              user.displayName = "Unsupported Feature Tester";
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
              t.description = "unsupported-feature";
              t.persistAndFlush();
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG_NAME)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "unsupported-feature")
                  .executeUpdate();
            });
  }

  @Test
  void scheduleListReturns501() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(SCHEDULES)
        .then()
        .statusCode(501)
        .body("code", equalTo(501))
        .body("message", equalTo("stack schedules are not supported by this backend"));
  }

  @Test
  void webhookListReturns501() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(HOOKS)
        .then()
        .statusCode(501)
        .body("code", equalTo(501))
        .body("message", equalTo("stack webhooks are not supported by this backend"));
  }

  @Test
  void schedulesWithoutTokenReturns401() {
    given().when().get(SCHEDULES).then().statusCode(401);
  }

  @Test
  void hooksWithoutTokenReturns401() {
    given().when().get(HOOKS).then().statusCode(401);
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
