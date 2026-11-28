package app.statedb.app.console;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.User;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class TokensResourceTest extends DatabaseTest {

  @BeforeEach
  void seedProvider() {
    OidcConsoleSessionFixture.seedProvider();
  }

  @Test
  void deactivatedSessionCannotListOrRevokeTokens() {
    Cookie session = OidcConsoleSessionFixture.login();
    UUID tokenId =
        requiringNew()
            .call(
                () -> {
                  User user = OidcConsoleSessionFixture.fixtureUser();
                  ApiToken token = new ApiToken();
                  token.tokenHash = "deactivated-session-token-" + UUID.randomUUID();
                  token.description = "must survive rejected revocation";
                  token.userId = user.id;
                  token.kind = ApiToken.Kind.oidc;
                  token.expiresAt = Instant.now().plusSeconds(3600);
                  token.persistAndFlush();
                  user.deactivatedAt = Instant.now();
                  return token.id;
                });

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/tokens")
        .then()
        .statusCode(401);
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .delete("/api/tokens/" + tokenId)
        .then()
        .statusCode(401);

    assertEquals(1L, ApiToken.count("id", tokenId), "rejected revocation leaves the token intact");
  }
}
