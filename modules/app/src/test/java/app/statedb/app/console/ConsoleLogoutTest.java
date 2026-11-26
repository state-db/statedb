package app.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import jakarta.transaction.Transactional;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Console sign-out ({@code GET /api/auth/logout}). Sign-out is a local, stateless act: it expires
 * the browser's session cookie and redirects to the SPA sign-in screen. Because the session is a
 * stateless encrypted cookie with no server-side record, the endpoint cannot invalidate the cookie
 * value itself — the assertions target the {@code Set-Cookie} deletion header and the redirect, not
 * loss of access for a replayed cookie.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class ConsoleLogoutTest {

  @BeforeEach
  @Transactional
  void seedProvider() {
    OidcConsoleSessionFixture.seedProvider();
  }

  @AfterEach
  @Transactional
  void cleanup() {
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void logoutExpiresOidcSessionCookieAndRedirectsToLogin() {
    Cookie session = OidcConsoleSessionFixture.login();

    Response logout =
        given()
            .redirects()
            .follow(false)
            .cookie(session.getName(), session.getValue())
            .accept("text/html")
            .when()
            .get("/api/auth/logout");

    logout.then().statusCode(303);
    assertEquals("/console/login", URI.create(logout.getHeader("Location")).getPath());

    Cookie cleared =
        logout.getDetailedCookies().asList().stream()
            .filter(cookie -> cookie.getName().equals(session.getName()))
            .findFirst()
            .orElse(null);
    assertNotNull(cleared, "logout re-sets the session cookie to delete it");
    assertEquals(0, cleared.getMaxAge(), "session cookie expired (Max-Age=0)");
    assertEquals("/api/", cleared.getPath(), "deletion targets the /api/ cookie path");
  }

  @Test
  void logoutExpiresAbandonedCodeFlowStateCookies() {
    // Nothing else ever clears these, so an abandoned flow leaves one behind for good.
    Response logout =
        given()
            .redirects()
            .follow(false)
            .cookie("q_auth_okta", "stale-state")
            .cookie("q_post_logout_okta", "stale-state")
            .accept("text/html")
            .when()
            .get("/api/auth/logout");

    logout.then().statusCode(303);
    for (String name : new String[] {"q_auth_okta", "q_post_logout_okta"}) {
      Cookie cleared =
          logout.getDetailedCookies().asList().stream()
              .filter(cookie -> cookie.getName().equals(name))
              .findFirst()
              .orElse(null);
      assertNotNull(cleared, name + " is re-set to delete it");
      assertEquals(0, cleared.getMaxAge(), name + " expired (Max-Age=0)");
      assertEquals("/api/", cleared.getPath(), name + " deletion targets the /api/ cookie path");
    }
  }

  @Test
  void logoutIsIdempotentForAnonymousCaller() {
    Response logout =
        given().redirects().follow(false).accept("text/html").when().get("/api/auth/logout");

    // No session challenge: a public, side-effect-light redirect even with no cookie to clear.
    logout.then().statusCode(303);
    assertEquals("/console/login", URI.create(logout.getHeader("Location")).getPath());
    assertTrue(
        logout.getDetailedCookies().asList().isEmpty(), "nothing to expire when unauthenticated");
  }
}
