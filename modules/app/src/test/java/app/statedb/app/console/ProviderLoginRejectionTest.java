package app.statedb.app.console;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The login route rejects unknown and disabled providers with a 404 (the {@code ProviderRouteGuard}
 * runs before quarkus-oidc would try to start a flow).
 */
@QuarkusTest
class ProviderLoginRejectionTest extends DatabaseTest {

  private static final String DISABLED = "rejection-disabled";

  @BeforeEach
  void seedDisabledProvider() {
    requiringNew()
        .run(
            () -> {
              OidcProvider p = new OidcProvider();
              p.name = DISABLED;
              p.issuerUrl = "https://disabled.example";
              p.clientId = "cid";
              p.clientSecret = "sec";
              p.scopes = List.of("openid");
              p.enabled = false;
              p.persist();
            });
  }

  @Test
  void unknownProviderLoginIs404() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/api/auth/login/no-such-provider")
        .then()
        .statusCode(404);
  }

  @Test
  void disabledProviderLoginIs404() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/api/auth/login/" + DISABLED)
        .then()
        .statusCode(404);
  }
}
