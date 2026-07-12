package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.statedb.persistence.identity.OidcProvider;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
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
 * End-to-end console lifecycle of a CI service account: an org owner mints one from their SSO
 * session, the returned token drives the Pulumi surface, and revoking kills it. Session
 * establishment mirrors {@link CliLoginFlowTest}.
 */
@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class ServiceAccountsResourceTest {

  private static final String PROVIDER = "wiremock";
  private static final String FIXTURE_ISS = "https://server.example.com";
  private static final String FIXTURE_SUB = "123456";
  private static final String ORG = "svc-console-org";
  private static final String SA_PATH = "/api/orgs/" + ORG + "/service-accounts";

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

              Organization org = new Organization();
              org.name = ORG;
              org.displayName = "Service Console Test";
              org.persistAndFlush();
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
              em.createNativeQuery("DELETE FROM users WHERE iss = 'local:service'").executeUpdate();
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, FIXTURE_ISS)
                  .setParameter(2, FIXTURE_SUB)
                  .executeUpdate();
            });
  }

  @Test
  void ownerMintsListsAndRevokes() {
    Cookie session = establishSession(OrgMember.Role.owner);

    // Mint: the raw token appears exactly once, in this response.
    Response created =
        given()
            .cookie(session.getName(), session.getValue())
            .contentType("application/json")
            .body(Map.of("name", "GitHub Actions deploy"))
            .when()
            .post(SA_PATH);
    created.then().statusCode(200);
    String accountId = created.path("id");
    String token = created.path("token");
    assertNotNull(token, "create response carries the raw token");

    // The minted token drives the Pulumi surface.
    given()
        .header("Authorization", "token " + token)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200);

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(SA_PATH)
        .then()
        .statusCode(200)
        .body("size()", org.hamcrest.Matchers.is(1))
        .body("[0].id", org.hamcrest.Matchers.equalTo(accountId))
        .body("[0].name", org.hamcrest.Matchers.equalTo("GitHub Actions deploy"))
        .body("[0].token", org.hamcrest.Matchers.nullValue());

    // Revoke: 204, the token dies, and a second revoke is 404.
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .delete(SA_PATH + "/" + accountId)
        .then()
        .statusCode(204);
    given()
        .header("Authorization", "token " + token)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(401);
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .delete(SA_PATH + "/" + accountId)
        .then()
        .statusCode(404);
  }

  @Test
  void nonOwnerCannotManageServiceAccounts() {
    Cookie session = establishSession(OrgMember.Role.member);

    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("name", "sneaky"))
        .when()
        .post(SA_PATH)
        .then()
        .statusCode(403);

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(SA_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  void anonymousIsChallengedNotServed() {
    given()
        .contentType("application/json")
        .body(Map.of("name", "nope"))
        .when()
        .post(SA_PATH)
        .then()
        .statusCode(401);
  }

  // Drives the console code flow to a usable session cookie, then grants the fixture user the
  // requested role in the test org. Mirrors CliLoginFlowTest.
  private Cookie establishSession(OrgMember.Role role) {
    Response start =
        given()
            .redirects()
            .follow(false)
            .accept("text/html")
            .when()
            .get("/api/auth/flow/" + PROVIDER);
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

    Cookie session =
        callback.getDetailedCookies().asList().stream()
            .filter(c -> c.getName().startsWith("q_session"))
            .findFirst()
            .orElse(null);
    assertNotNull(session, "session cookie set on callback");

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              User user =
                  User.<User>find("iss = ?1 and sub = ?2", FIXTURE_ISS, FIXTURE_SUB).firstResult();
              assertNotNull(user, "callback provisions the user");
              Organization org = Organization.<Organization>find("name", ORG).firstResult();
              OrgMember m = new OrgMember();
              m.orgId = org.id;
              m.userId = user.id;
              m.role = role;
              m.persistAndFlush();
              assertEquals(1L, OrgMember.count("userId", user.id));
            });
    return session;
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
