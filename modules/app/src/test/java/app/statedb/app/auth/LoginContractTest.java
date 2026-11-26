package app.statedb.app.auth;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import app.statedb.app.contract.PulumiContractIdentity;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.User;
import io.quarkus.test.junit.QuarkusTest;
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

  private static final PulumiContractIdentity IDENTITY =
      new PulumiContractIdentity(
          ORG_NAME,
          "Login Contract Test",
          "login-contract",
          "contract@example.com",
          "Contract Tester",
          RAW_TOKEN,
          "login-contract");

  @BeforeEach
  void seed() {
    IDENTITY.seed();
  }

  @AfterEach
  void cleanup() {
    IDENTITY.cleanup();
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
        .body("githubLogin", equalTo("Contract Tester"))
        .body("organizations", hasSize(1))
        .body("organizations[0].githubLogin", equalTo(ORG_NAME))
        .body("organizations[0].name", equalTo(ORG_NAME));
  }

  @Test
  void apiUserReportsADisplayNameForConsoleMintedTokensToo() {
    // `pulumi login` mints an oidc-kind token, whose sub is whatever the IdP issued — usually a
    // UUID. whoami must not print that.
    String rawOidcToken = "login-contract-oidc-token";
    seedOidcToken(rawOidcToken);

    given()
        .header("Authorization", "token " + rawOidcToken)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("githubLogin", equalTo("Contract Tester"))
        .body("name", equalTo("Contract Tester"));
  }

  /**
   * The CLI assigns unqualified stack names ({@code pulumi stack init dev}) to this org, and only
   * reads it from a top-level {@code GitHubLogin}; otherwise it falls back to the {@code whoami}
   * display name, which is not an org.
   */
  @Test
  void apiUserOrganizationsDefaultNamesTheMemberOrgNotTheDisplayName() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user/organizations/default")
        .then()
        .statusCode(200)
        .body("GitHubLogin", equalTo(ORG_NAME))
        .body("$", not(hasKey("organization")));
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

  private void seedOidcToken(String rawOidcToken) {
    tx(
        () -> {
          User user =
              User.find("iss = ?1 and sub = ?2", "local:test", "login-contract").firstResult();
          ApiToken t = new ApiToken();
          t.tokenHash = sha256Hex(rawOidcToken);
          t.userId = user.id;
          t.kind = ApiToken.Kind.oidc;
          t.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
          t.description = "login-contract-oidc";
          t.persistAndFlush();
        });
  }
}
