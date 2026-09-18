package app.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.OidcProvider;
import app.statedb.persistence.identity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Full console code flow against the in-JVM mock OIDC provider ({@link OidcWiremockTestResource}),
 * driving every hop with RestAssured (no headless browser): login redirect → IdP credential POST →
 * callback → session cookie → {@code /api/user}. Exercises the DB-backed dynamic tenant resolver,
 * the BFF session, and the current-user shape end to end.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class OidcLoginFlowTest {

  private static final String PROVIDER = "wiremock";

  @Inject EntityManager em;

  private String issuer() {
    // OidcWiremockTestResource exposes its base (already ending in /auth) as keycloak.url; the
    // realm issuer is <keycloak.url>/realms/quarkus.
    return ConfigProvider.getConfig().getValue("keycloak.url", String.class) + "/realms/quarkus";
  }

  @BeforeEach
  void seedProvider() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM oidc_providers WHERE name = ?1")
                  .setParameter(1, PROVIDER)
                  .executeUpdate();
              OidcProvider p = new OidcProvider();
              p.name = PROVIDER;
              p.issuerUrl = issuer();
              p.clientId = "quarkus-app"; // the mock IdP's registered client + audience
              p.clientSecret = "secret";
              p.scopes = List.of("openid", "profile", "email");
              p.enabled = true;
              p.persist();
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM oidc_providers WHERE name = ?1")
                  .setParameter(1, PROVIDER)
                  .executeUpdate();
              // OidcWiremockTestResource signs its fixture ID token with this fixed issuer/subject.
              em.createNativeQuery(
                      "DELETE FROM users WHERE iss = 'https://server.example.com' AND sub = '123456'")
                  .executeUpdate();
            });
  }

  @Test
  void loginEstablishesSessionScopedToApiAndUserReturnsShape() {
    // These are browser navigations, so they accept HTML — quarkus-oidc issues a 302 redirect for
    // navigation requests; anonymous console API requests remain a bare 401.
    // 1a. Public login route validates the provider and redirects to the @Authenticated flow route.
    Response defaultLogin =
        given().redirects().follow(false).accept("text/html").when().get("/api/auth/login");
    defaultLogin.then().statusCode(303);
    assertEquals(
        "/api/auth/flow/" + PROVIDER + "?returnTo=%2Fconsole",
        pathWithQuery(defaultLogin.getHeader("Location")));

    Response login =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .when()
            .get("/api/auth/login/" + PROVIDER);
    login.then().statusCode(303); // seeOther
    assertEquals(
        "/api/auth/flow/" + PROVIDER + "?returnTo=%2Fconsole",
        pathWithQuery(login.getHeader("Location")));

    // 1b. The flow route hands off to quarkus-oidc: 302 to the mock IdP, plus the OIDC state
    // cookie.
    Response start =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .when()
            .get("/api/auth/flow/" + PROVIDER);
    start.then().statusCode(302);
    String authorizeUrl = start.getHeader("Location");
    assertTrue(authorizeUrl.startsWith(issuer()), "redirected to the provider issuer");
    Map<String, String> stateCookies = start.getCookies();

    Map<String, String> authQuery = queryParams(authorizeUrl);
    String state = authQuery.get("state");
    String redirectUri = authQuery.get("redirect_uri");
    assertNotNull(state, "authorize request carries state");

    // The provider's configured scopes (openid profile email) must reach the IdP, otherwise no
    // profile/email claims come back.
    String scope = authQuery.get("scope");
    assertNotNull(scope, "authorize request carries scope");
    assertTrue(scope.contains("openid"), "scope=" + scope);
    assertTrue(scope.contains("profile"), "scope=" + scope);
    assertTrue(scope.contains("email"), "scope=" + scope);

    // 2. "Submit" the mock IdP login form. Its action is a GET to /login that echoes state +
    // redirect_uri from the query and 302s back with an auth code (credentials are not checked).
    Response idp =
        given()
            .redirects()
            .follow(false)
            .queryParam("username", "alice")
            .queryParam("password", "alice")
            .queryParam("state", state)
            .queryParam("redirect_uri", redirectUri)
            .when()
            .get(origin(authorizeUrl) + "/login");
    idp.then().statusCode(302);
    String callbackUrl = idp.getHeader("Location");

    // 3. Hit the callback with the state cookie. quarkus-oidc exchanges the code, invokes the
    // AuthenticationCompletionAction (which provisions the user), sets the session cookie scoped
    // to /api/, and restores the original /flow URL.
    var callbackReq = given().redirects().follow(false).accept("text/html");
    stateCookies.forEach(callbackReq::cookie);
    Response callback = callbackReq.when().get(pathWithQuery(callbackUrl));
    callback.then().statusCode(302);

    Cookie session = sessionCookie(callback);
    assertNotNull(session, "session cookie set on callback");
    assertEquals("/api/", session.getPath(), "session cookie scoped to /api/");
    String restored = callback.getHeader("Location");
    assertTrue(
        pathWithQuery(restored).startsWith("/api/auth/flow/" + PROVIDER),
        "callback restores the flow URL, got " + restored);

    // Provisioning is part of authentication completion, not a later browser navigation. If the
    // browser never follows the restored /flow redirect, the issued session must still be usable.
    long provisionedUsers =
        QuarkusTransaction.requiringNew()
            .call(
                () -> User.count("iss = ?1 and sub = ?2", "https://server.example.com", "123456"));
    assertEquals(1L, provisionedUsers, "callback completion provisions the user atomically");

    // 4. /api/user works immediately with the callback's session, without following /flow. The mock
    // id token carries no email/name claims, so those are null; id/siteAdmin/organizations remain.
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/user")
        .then()
        .statusCode(200)
        .body("id", not(emptyOrNullString()))
        .body("iss", equalTo("https://server.example.com"))
        .body("sub", equalTo("123456"))
        .body("siteAdmin", equalTo(false))
        .body("organizations", hasSize(0));
  }

  private static Cookie sessionCookie(Response response) {
    return response.getDetailedCookies().asList().stream()
        .filter(c -> c.getName().startsWith("q_session"))
        .findFirst()
        .orElse(null);
  }

  private static String origin(String url) {
    URI u = URI.create(url);
    String authority = u.getPort() == -1 ? u.getHost() : u.getHost() + ":" + u.getPort();
    return u.getScheme() + "://" + authority;
  }

  private static String pathWithQuery(String url) {
    URI u = URI.create(url);
    return u.getQuery() == null ? u.getPath() : u.getPath() + "?" + u.getRawQuery();
  }

  private static Map<String, String> queryParams(String url) {
    Map<String, String> params = new HashMap<>();
    String query = URI.create(url).getRawQuery();
    if (query == null) {
      return params;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        params.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return params;
  }
}
