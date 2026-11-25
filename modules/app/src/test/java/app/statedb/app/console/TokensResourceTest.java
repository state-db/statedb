package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.User;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class TokensResourceTest {

  private UUID tokenId;

  @BeforeEach
  void seedProvider() {
    OidcConsoleSessionFixture.seedProvider();
  }

  @AfterEach
  void cleanup() {
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void deactivatedSessionCannotListOrRevokeTokens() {
    Cookie session = OidcConsoleSessionFixture.login();
    tx(
        () -> {
          User user =
              User.<User>find(
                      "iss = ?1 and sub = ?2",
                      OidcConsoleSessionFixture.FIXTURE_ISS,
                      OidcConsoleSessionFixture.FIXTURE_SUB)
                  .firstResult();
          ApiToken token = new ApiToken();
          token.tokenHash = "deactivated-session-token-" + UUID.randomUUID();
          token.description = "must survive rejected revocation";
          token.userId = user.id;
          token.kind = ApiToken.Kind.oidc;
          token.expiresAt = Instant.now().plusSeconds(3600);
          token.persistAndFlush();
          tokenId = token.id;
          user.deactivatedAt = Instant.now();
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

    assertEquals(
        1L,
        read(() -> ApiToken.count("id", tokenId)),
        "rejected revocation leaves the token intact");
  }
}
