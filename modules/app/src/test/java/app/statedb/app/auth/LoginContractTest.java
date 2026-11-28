package app.statedb.app.auth;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiTokenHasher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of the three {@code pulumi login} endpoints against the recorded fixtures
 * under {@code tools/pulumi/recorder/fixtures/by-command/01-login/}.
 */
@QuarkusTest
class LoginContractTest extends DatabaseTest {

  private static final String ORG_NAME = "login-contract-org";
  private static final String RAW_TOKEN = "login-contract-token";

  @Inject ApiTokenHasher tokenHasher;

  private TestDb.SeededOwner owner;

  @BeforeEach
  void seed() {
    owner = db.seedOwner(ORG_NAME, RAW_TOKEN);
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
        .body("email", equalTo(owner.email()))
        .body("name", equalTo(owner.displayName()))
        .body("githubLogin", equalTo(owner.displayName()))
        .body("organizations", hasSize(1))
        .body("organizations[0].githubLogin", equalTo(ORG_NAME))
        .body("organizations[0].name", equalTo(ORG_NAME));
  }

  @Test
  void apiUserReportsADisplayNameForConsoleMintedTokensToo() {
    // `pulumi login` mints an oidc-kind token, whose sub is whatever the IdP issued — usually a
    // UUID. whoami must not print that.
    String rawOidcToken = "login-contract-oidc-token";
    requiringNew()
        .run(
            () -> {
              ApiToken t = new ApiToken();
              t.tokenHash = tokenHasher.hash(rawOidcToken);
              t.userId = owner.userId();
              t.kind = ApiToken.Kind.oidc;
              t.expiresAt = Instant.now().plusSeconds(60 * 60);
              t.description = "login-contract-oidc";
              t.persistAndFlush();
            });

    given()
        .header("Authorization", "token " + rawOidcToken)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("githubLogin", equalTo(owner.displayName()))
        .body("name", equalTo(owner.displayName()));
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
}
