package app.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the native {@code pulumi login} bridge: the root {@code /cli-login} shim,
 * the authenticated {@code /api/auth/cli-login} handler that asks the user to approve CLI access,
 * the approval POST that mints and redirects to the CLI's loopback listener, and the sign-in
 * redirect for anonymous callers. Drives the console SSO login against the in-JVM mock IdP ({@link
 * OidcWiremockTestResource}) with RestAssured, then proves the minted token authenticates the
 * Pulumi surface — the same handshake {@code pulumi login} performs.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class CliLoginFlowTest {

  private static final String PROVIDER = "wiremock";
  private static final String FIXTURE_ISS = "https://server.example.com";
  private static final String FIXTURE_SUB = "123456";

  @Inject EntityManager em;

  private String issuer() {
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
              p.clientId = "quarkus-app";
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
              em.createNativeQuery(
                      "DELETE FROM api_tokens WHERE user_id IN"
                          + " (SELECT id FROM users WHERE iss = ?1 AND sub = ?2)")
                  .setParameter(1, FIXTURE_ISS)
                  .setParameter(2, FIXTURE_SUB)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, FIXTURE_ISS)
                  .setParameter(2, FIXTURE_SUB)
                  .executeUpdate();
            });
  }

  @Test
  void rootShimBouncesIntoApiSurfacePreservingSessionParams() {
    Response shim =
        given()
            .redirects()
            .follow(false)
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc")
            .queryParam("cliSessionDescription", "my laptop")
            .when()
            .get("/cli-login");
    shim.then().statusCode(303);

    String location = shim.getHeader("Location");
    Map<String, String> q = queryParams(location);
    assertTrue(
        URI.create(location).getPath().equals("/api/auth/cli-login"),
        "bounces to the /api/ handler, got " + location);
    assertEquals("59123", q.get("cliSessionPort"));
    assertEquals("nonce-abc", q.get("cliSessionNonce"));
    assertEquals("my laptop", q.get("cliSessionDescription"));
  }

  @Test
  void rootShimRejectsMissingOrBadParams() {
    given()
        .redirects()
        .follow(false)
        .queryParam("cliSessionPort", "59123")
        .when()
        .get("/cli-login")
        .then()
        .statusCode(400); // no nonce

    given()
        .redirects()
        .follow(false)
        .queryParam("cliSessionPort", "not-a-port")
        .queryParam("cliSessionNonce", "nonce-abc")
        .when()
        .get("/cli-login")
        .then()
        .statusCode(400); // bad port
  }

  @Test
  void anonymousHandlerSignsInThroughDefaultProviderThenShowsApproval() {
    // A top-level navigation with no session goes through SSO and lands back on the approval page,
    // never on the SPA-style 401 a fetch would get.
    Response anonymous = cliLoginPage(null, "my laptop");
    anonymous.then().statusCode(303);
    String flow = anonymous.getHeader("Location");
    assertEquals("/api/auth/flow/" + PROVIDER, URI.create(flow).getPath());
    String returnTo = queryParams(flow).get("returnTo");
    assertEquals("/api/auth/cli-login", URI.create(returnTo).getPath());
    Map<String, String> q = queryParams(returnTo);
    assertEquals("59123", q.get("cliSessionPort"));
    assertEquals("nonce-abc", q.get("cliSessionNonce"));
    assertEquals("my laptop", q.get("cliSessionDescription"));
    assertEquals("1", q.get("afterSignIn"));

    SignIn signIn = signIn(pathWithQuery(flow));
    Response restored =
        given()
            .urlEncodingEnabled(false)
            .redirects()
            .follow(false)
            .cookie(signIn.session().getName(), signIn.session().getValue())
            .accept("text/html")
            .when()
            .get(pathWithQuery(signIn.callback().getHeader("Location")));
    restored.then().statusCode(303);
    assertEquals(
        returnTo, pathWithQuery(restored.getHeader("Location")), "the flow returns to cli-login");

    given()
        .urlEncodingEnabled(false)
        .redirects()
        .follow(false)
        .cookie(signIn.session().getName(), signIn.session().getValue())
        .accept("text/html")
        .when()
        .get(pathWithQuery(restored.getHeader("Location")))
        .then()
        .statusCode(200)
        .body(containsString("Allow CLI access?"))
        .body(containsString("my laptop"));
  }

  @Test
  void anonymousHandlerUsesSignInScreenWhenDefaultProviderIsDisabled() {
    setProviderEnabled(false);
    assertRedirectsToSignInScreen(cliLoginPage(null, null));
  }

  @Test
  void anonymousReturnAfterSignInEndsInErrorPageNotLoop() {
    given()
        .redirects()
        .follow(false)
        .accept("text/html")
        .queryParam("cliSessionPort", "59123")
        .queryParam("cliSessionNonce", "nonce-abc")
        .queryParam("afterSignIn", "1")
        .when()
        .get("/api/auth/cli-login")
        .then()
        .statusCode(401)
        .header("Location", nullValue())
        .contentType(containsString("text/html"))
        .body(containsString("Sign-in was not completed"));
  }

  @Test
  void otherConsoleRoutesStill401ForAnonymousCallers() {
    given().redirects().follow(false).when().get("/api/user").then().statusCode(401);
    given()
        .redirects()
        .follow(false)
        .contentType("application/x-www-form-urlencoded")
        .formParam("decision", "allow")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(401);
  }

  @Test
  void approvalPageEscapesClientDescription() {
    Cookie session = establishSession();
    String payload = "<script>alert(1)</script>\"><img src=x onerror=alert(2)>";
    String page = cliLoginPage(session, payload).then().statusCode(200).extract().asString();
    assertFalse(page.contains("<script>alert(1)</script>"), "description must not render raw");
    assertFalse(page.contains("<img src=x"), "description must not render raw");
    assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "description is escaped");
  }

  @Test
  void authenticatedHandlerRequiresApprovalBeforeMintingToken() {
    Cookie session = establishSession();

    Response confirmation =
        given()
            .redirects()
            .follow(false)
            .cookie(session.getName(), session.getValue())
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc")
            .queryParam("cliSessionDescription", "my laptop")
            .when()
            .get("/api/auth/cli-login");
    confirmation
        .then()
        .statusCode(200)
        .body(containsString("Allow CLI access?"))
        .body(containsString("my laptop"))
        .body(containsString("127.0.0.1:59123"));

    long tokensAfterGet =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    (Long)
                        em.createQuery(
                                "select count(t) from ApiToken t join User u on u.id = t.userId"
                                    + " where u.iss = :iss and u.sub = :sub")
                            .setParameter("iss", FIXTURE_ISS)
                            .setParameter("sub", FIXTURE_SUB)
                            .getSingleResult());
    assertEquals(0L, tokensAfterGet, "GET confirmation must not mint a token");

    String signedRequest = hiddenInput(confirmation.asString(), "request");
    Response mint =
        approvalPost(session, confirmation)
            .formParam("request", signedRequest)
            .formParam("decision", "allow")
            .when()
            .post("/api/auth/cli-login/approve");
    mint.then().statusCode(302);

    String location = mint.getHeader("Location");
    URI loopback = URI.create(location);
    assertEquals("127.0.0.1", loopback.getHost(), "redirect host is hard-coded loopback");
    assertEquals(59123, loopback.getPort());

    Map<String, String> q = queryParams(location);
    assertEquals("nonce-abc", q.get("nonce"), "echoes the CLI's nonce for its listener to verify");
    String accessToken = q.get("accessToken");
    assertNotNull(accessToken, "redirect carries the minted token");

    // The minted token authenticates the Pulumi surface — the GET /api/user that `pulumi login`
    // performs immediately after capturing the token.
    given()
        .header("Authorization", "token " + accessToken)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("githubLogin", containsString(FIXTURE_SUB));
  }

  @Test
  void cliMintIsJsonConsoleMutation() {
    Cookie session = establishSession();

    Response mint =
        given()
            .cookie(session.getName(), session.getValue())
            .header("Origin", "http://statedb.test")
            .contentType("application/json")
            .body("{}")
            .when()
            .post("/api/auth/cli-mint");
    mint.then().statusCode(200);

    String tokenId = mint.jsonPath().getString("id");
    String accessToken = mint.jsonPath().getString("token");
    assertNotNull(tokenId, "mint response carries the token id");
    assertNotNull(accessToken, "mint response reveals the raw token once");
    assertFalse(accessToken.isBlank(), "raw token must not be blank");

    given()
        .header("Authorization", "token " + accessToken)
        .header("Accept", "application/vnd.pulumi+9")
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("githubLogin", containsString(FIXTURE_SUB));
  }

  @Test
  void secondApprovalPageDoesNotInvalidateTheFirst() {
    Cookie session = establishSession();
    Response firstTab = approvalPage(session, null, "59123", "nonce-tab-1");
    Cookie firstCsrf = firstTab.getDetailedCookie("state-db-csrf");
    assertNotNull(firstCsrf, "approval page must set a CSRF cookie");

    // The browser replays the cookie it already holds; the token must be reused, not rotated.
    Response secondTab = approvalPage(session, firstCsrf, "59124", "nonce-tab-2");
    Cookie secondCsrf = secondTab.getDetailedCookie("state-db-csrf");
    assertNotNull(secondCsrf, "approval page must refresh the CSRF cookie");
    assertEquals(firstCsrf.getValue(), secondCsrf.getValue(), "CSRF token is reused across tabs");

    // Submitting the form left open in the first tab still works.
    approvalPost(session, firstTab)
        .formParam("request", hiddenInput(firstTab.asString(), "request"))
        .formParam("decision", "allow")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(302);
  }

  @Test
  void approvalRequestIsSingleUse() {
    Cookie session = establishSession();
    Response confirmation =
        given()
            .redirects()
            .follow(false)
            .cookie(session.getName(), session.getValue())
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc")
            .when()
            .get("/api/auth/cli-login");
    confirmation.then().statusCode(200);
    Cookie issued = confirmation.getDetailedCookie("state-db-csrf");
    assertNotNull(issued, "approval page must set a CSRF cookie");
    assertTrue(issued.isHttpOnly(), "CSRF cookie is HttpOnly");
    assertEquals("Lax", issued.getSameSite(), "CSRF cookie is SameSite=Lax");
    String signedRequest = hiddenInput(confirmation.asString(), "request");

    given()
        .redirects()
        .follow(false)
        .cookie(session.getName(), session.getValue())
        .contentType("application/x-www-form-urlencoded")
        .formParam("request", signedRequest)
        .formParam("decision", "allow")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(400);

    Cookie csrf = confirmation.getDetailedCookie("state-db-csrf");
    assertNotNull(csrf, "approval page must set a CSRF cookie");
    given()
        .redirects()
        .follow(false)
        .cookie(session.getName(), session.getValue())
        .cookie(csrf.getName(), csrf.getValue())
        .contentType("application/x-www-form-urlencoded")
        .formParam("state-db-csrf", "not*base64!!")
        .formParam("request", signedRequest)
        .formParam("decision", "allow")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(400);

    approvalPost(session, confirmation)
        .formParam("request", signedRequest)
        .formParam("decision", "allow")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(302);

    // Replaying the same signed request must not mint a second token.
    approvalPost(session, confirmation)
        .formParam("request", signedRequest)
        .formParam("decision", "allow")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(400);

    long tokens =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    (Long)
                        em.createQuery(
                                "select count(t) from ApiToken t join User u on u.id = t.userId"
                                    + " where u.iss = :iss and u.sub = :sub")
                            .setParameter("iss", FIXTURE_ISS)
                            .setParameter("sub", FIXTURE_SUB)
                            .getSingleResult());
    assertEquals(1L, tokens, "replay must not mint a second token");
  }

  @Test
  void cancellingApprovalDoesNotMintToken() {
    Cookie session = establishSession();
    Response confirmation =
        given()
            .redirects()
            .follow(false)
            .cookie(session.getName(), session.getValue())
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc")
            .when()
            .get("/api/auth/cli-login");
    confirmation.then().statusCode(200);

    approvalPost(session, confirmation)
        .formParam("request", hiddenInput(confirmation.asString(), "request"))
        .formParam("decision", "cancel")
        .when()
        .post("/api/auth/cli-login/approve")
        .then()
        .statusCode(200)
        .body(containsString("No token was created"));

    long tokens =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    (Long)
                        em.createQuery(
                                "select count(t) from ApiToken t join User u on u.id = t.userId"
                                    + " where u.iss = :iss and u.sub = :sub")
                            .setParameter("iss", FIXTURE_ISS)
                            .setParameter("sub", FIXTURE_SUB)
                            .getSingleResult());
    assertEquals(0L, tokens);
  }

  @Test
  void welcomePageRenders() {
    given().when().get("/welcome/cli").then().statusCode(200).body(containsString("logged in"));
  }

  // Drives the console code flow to a usable session cookie, mirroring OidcLoginFlowTest.
  private Cookie establishSession() {
    return signIn("/api/auth/flow/" + PROVIDER).session();
  }

  private record SignIn(Cookie session, Response callback) {}

  private SignIn signIn(String flowPath) {
    // flowPath arrives already encoded.
    Response start =
        given()
            .urlEncodingEnabled(false)
            .redirects()
            .follow(false)
            .accept("text/html")
            .when()
            .get(flowPath);
    start.then().statusCode(302);
    String authorizeUrl = start.getHeader("Location");
    Map<String, String> stateCookies = start.getCookies();
    Map<String, String> authQuery = queryParams(authorizeUrl);

    Response idp =
        given()
            .redirects()
            .follow(false)
            .queryParam("username", "alice")
            .queryParam("password", "alice")
            .queryParam("state", authQuery.get("state"))
            .queryParam("redirect_uri", authQuery.get("redirect_uri"))
            .when()
            .get(origin(authorizeUrl) + "/login");
    idp.then().statusCode(302);

    var callbackReq = given().redirects().follow(false).accept("text/html");
    stateCookies.forEach(callbackReq::cookie);
    Response callback = callbackReq.when().get(pathWithQuery(idp.getHeader("Location")));
    callback.then().statusCode(302);

    Cookie session = sessionCookie(callback);
    assertNotNull(session, "session cookie set on callback");

    long provisioned =
        QuarkusTransaction.requiringNew()
            .call(() -> User.count("iss = ?1 and sub = ?2", FIXTURE_ISS, FIXTURE_SUB));
    assertEquals(1L, provisioned, "callback provisions the user");
    return new SignIn(session, callback);
  }

  private void setProviderEnabled(boolean enabled) {
    QuarkusTransaction.requiringNew()
        .run(() -> OidcProvider.update("enabled = ?1 where name = ?2", enabled, PROVIDER));
  }

  private static Response cliLoginPage(Cookie session, String description) {
    RequestSpecification request =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .queryParam("cliSessionPort", "59123")
            .queryParam("cliSessionNonce", "nonce-abc");
    if (session != null) {
      request.cookie(session.getName(), session.getValue());
    }
    if (description != null) {
      request.queryParam("cliSessionDescription", description);
    }
    return request.when().get("/api/auth/cli-login");
  }

  static void assertRedirectsToSignInScreen(Response response) {
    response.then().statusCode(303);
    String location = response.getHeader("Location");
    assertEquals("/console/login", URI.create(location).getPath(), "got " + location);
    String returnTo = queryParams(location).get("returnTo");
    assertEquals("/api/auth/cli-login", URI.create(returnTo).getPath());
    assertEquals("59123", queryParams(returnTo).get("cliSessionPort"));
    assertEquals("1", queryParams(returnTo).get("afterSignIn"));
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

  static Response approvalPage(Cookie session, Cookie csrf, String port, String nonce) {
    RequestSpecification request =
        given()
            .redirects()
            .follow(false)
            .cookie(session.getName(), session.getValue())
            .accept("text/html")
            .queryParam("cliSessionPort", port)
            .queryParam("cliSessionNonce", nonce);
    if (csrf != null) {
      request.cookie(csrf.getName(), csrf.getValue());
    }
    Response response = request.when().get("/api/auth/cli-login");
    response.then().statusCode(200);
    return response;
  }

  static RequestSpecification approvalPost(Cookie session, Response confirmation) {
    Cookie csrf = confirmation.getDetailedCookie("state-db-csrf");
    assertNotNull(csrf, "approval page must set a CSRF cookie");
    return given()
        .redirects()
        .follow(false)
        .cookie(session.getName(), session.getValue())
        .cookie(csrf.getName(), csrf.getValue())
        .contentType("application/x-www-form-urlencoded")
        .formParam("state-db-csrf", hiddenInput(confirmation.asString(), "state-db-csrf"));
  }

  static Map<String, String> queryParams(String url) {
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

  static String hiddenInput(String html, String name) {
    Pattern pattern =
        Pattern.compile("<input[^>]+name=\"" + Pattern.quote(name) + "\"[^>]+value=\"([^\"]+)\"");
    Matcher m = pattern.matcher(html);
    assertTrue(m.find(), "missing hidden input " + name);
    return m.group(1)
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&");
  }
}
