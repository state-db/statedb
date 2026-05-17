package io.statedb.app.auth;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Seeds rows in a committed transaction so the Quarkus server (separate connection) can see them;
 * {@code @TestTransaction} would invisibly hide them.
 */
@QuarkusTest
class TokenAuthFilterTest {

  private static final String ORG_NAME = "auth-filter-test-org";
  private static final String VALID_RAW_TOKEN = "auth-filter-test-token";
  private static final String EXPIRED_RAW_TOKEN = "auth-filter-test-token-expired";

  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Auth Filter Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "auth-filter";
              user.email = "auth@example.com";
              user.displayName = "Auth Tester";
              user.persistAndFlush();

              OrgMember m = new OrgMember();
              m.orgId = org.id;
              m.userId = user.id;
              m.role = OrgMember.Role.owner;
              m.persistAndFlush();

              insertToken(user.id, org.id, sha256Hex(VALID_RAW_TOKEN), null);
              insertToken(
                  user.id,
                  org.id,
                  sha256Hex(EXPIRED_RAW_TOKEN),
                  Instant.now().minus(1, ChronoUnit.MINUTES));
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
                  .setParameter(2, "auth-filter")
                  .executeUpdate();
            });
  }

  @Test
  void missingAuthorizationHeaderReturns401() {
    given().when().get("/pulumi/api/user").then().statusCode(401);
  }

  @Test
  void wrongAuthSchemeReturns401() {
    given()
        .header("Authorization", "Bearer " + VALID_RAW_TOKEN)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(401);
  }

  @Test
  void unknownTokenReturns401() {
    given()
        .header("Authorization", "token completely-unknown-token")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(401);
  }

  @Test
  void expiredTokenReturns401() {
    given()
        .header("Authorization", "token " + EXPIRED_RAW_TOKEN)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(401);
  }

  @Test
  void revokedMembershipReturns401() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "DELETE FROM org_members WHERE org_id ="
                            + " (SELECT id FROM organizations WHERE name = ?1)")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());

    given()
        .header("Authorization", "token " + VALID_RAW_TOKEN)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(401);
  }

  @Test
  void validTokenReturns200WithPrincipalOrgName() {
    given()
        .header("Authorization", "token " + VALID_RAW_TOKEN)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("githubLogin", equalTo("auth-filter"))
        .body("email", equalTo("auth@example.com"))
        .body("name", equalTo("Auth Tester"))
        .body("organizations[0].name", equalTo(ORG_NAME));
  }

  @Test
  void capabilitiesIsAlsoGated() {
    given().when().get("/pulumi/api/capabilities").then().statusCode(401);
    given()
        .header("Authorization", "token " + VALID_RAW_TOKEN)
        .when()
        .get("/pulumi/api/capabilities")
        .then()
        .statusCode(200);
  }

  @Test
  void stacksPrefixIsAlsoGated() {
    given().when().get("/pulumi/api/stacks/foo/bar/baz").then().statusCode(401);
  }

  private void insertToken(
      java.util.UUID userId, java.util.UUID orgId, String hash, Instant expiresAt) {
    ApiToken t = new ApiToken();
    t.tokenHash = hash;
    t.userId = userId;
    t.orgId = orgId;
    t.kind = ApiToken.Kind.service_account;
    t.description = "test";
    t.expiresAt = expiresAt;
    t.persistAndFlush();
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
