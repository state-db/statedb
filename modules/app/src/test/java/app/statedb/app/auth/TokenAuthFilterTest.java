package app.statedb.app.auth;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiTokenHasher;
import app.statedb.persistence.identity.OrgMember;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TokenAuthFilterTest extends DatabaseTest {

  private static final String ORG_NAME = "auth-filter-test-org";
  private static final String VALID_RAW_TOKEN = "auth-filter-test-token";
  private static final String EXPIRED_RAW_TOKEN = "auth-filter-test-token-expired";

  @Inject ApiTokenHasher tokenHasher;

  private TestDb.SeededOwner owner;

  @BeforeEach
  void seed() {
    owner = db.seedOwner(ORG_NAME, VALID_RAW_TOKEN);
    requiringNew()
        .run(
            () -> {
              ApiToken expired = new ApiToken();
              expired.tokenHash = tokenHasher.hash(EXPIRED_RAW_TOKEN);
              expired.userId = owner.userId();
              expired.orgId = owner.orgId();
              expired.kind = ApiToken.Kind.service_account;
              expired.description = "expired";
              expired.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
              expired.persistAndFlush();
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
    requiringNew().run(() -> OrgMember.delete("orgId", owner.orgId()));

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
        .body("githubLogin", equalTo(owner.displayName()))
        .body("email", equalTo(owner.email()))
        .body("name", equalTo(owner.displayName()))
        .body("organizations[0].name", equalTo(ORG_NAME));
  }

  @Test
  void successfulAuthAdvancesLastUsedAt() {
    given()
        .header("Authorization", "token " + VALID_RAW_TOKEN)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200);

    requiringNew()
        .run(
            () -> {
              ApiToken token =
                  ApiToken.<ApiToken>find("tokenHash", tokenHasher.hash(VALID_RAW_TOKEN))
                      .firstResult();
              assertNotNull(token.lastUsedAt, "auth must record last_used_at");
            });
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
}
