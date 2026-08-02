package app.statedb.app.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of the three {@code pulumi login} endpoints against the recorded fixtures
 * under {@code tools/pulumi/recorder/fixtures/by-command/01-login/}.
 */
@QuarkusTest
class LoginContractTest {

  private static final String ORG_NAME = "login-contract-org";
  private static final String RAW_TOKEN = "login-contract-token";

  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Login Contract Test";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "login-contract";
              user.email = "contract@example.com";
              user.displayName = "Contract Tester";
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
              t.description = "login-contract";
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
                  .setParameter(2, "login-contract")
                  .executeUpdate();
            });
  }

  /**
   * Mirrors {@code fixtures/.../0001_GET_api_user.json}; Accept header matches what the CLI sends.
   */
  @Test
  void apiUserShape() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("$", hasKey("email"))
        .body("$", hasKey("githubLogin"))
        .body("$", hasKey("name"))
        .body("$", hasKey("organizations"))
        .body("email", equalTo("contract@example.com"))
        .body("name", equalTo("Contract Tester"))
        .body("githubLogin", equalTo("login-contract"))
        .body("organizations", hasSize(1))
        .body("organizations[0].githubLogin", equalTo(ORG_NAME))
        .body("organizations[0].name", equalTo(ORG_NAME));
  }

  /** Mirrors {@code fixtures/.../0002_GET_api_user_organizations_default.json}. */
  @Test
  void apiUserOrganizationsDefaultShape() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user/organizations/default")
        .then()
        .statusCode(200)
        .body("$", hasKey("organization"))
        .body("organization.githubLogin", equalTo(ORG_NAME))
        .body("organization.name", equalTo(ORG_NAME));
  }

  @Test
  void apiCapabilitiesShape() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/capabilities")
        .then()
        .statusCode(200)
        .body("capabilities", hasSize(1))
        .body("capabilities[0].name", equalTo("delta-checkpoint-updates"))
        .body("capabilities[0].version", is(1));
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
