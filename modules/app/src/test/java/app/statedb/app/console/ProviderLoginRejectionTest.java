package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;

import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The login route rejects unknown and disabled providers with a 404 (the {@code ProviderRouteGuard}
 * runs before quarkus-oidc would try to start a flow). Seed rows are committed because the guard's
 * lookup runs in its own transaction and only sees committed data; we clean them up afterward.
 */
@QuarkusTest
class ProviderLoginRejectionTest {

  @Inject EntityManager em;

  private static final String DISABLED = "rejection-disabled";

  @BeforeEach
  void seedDisabledProvider() {
    tx(
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

  @AfterEach
  void cleanup() {
    tx(
        () ->
            em.createNativeQuery("DELETE FROM oidc_providers WHERE name = ?1")
                .setParameter(1, DISABLED)
                .executeUpdate());
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
