package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.persistence.identity.OidcProvider;
import app.statedb.persistence.identity.User;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;

final class OidcConsoleSessionFixture {
  static final String PROVIDER = "wiremock";
  static final String FIXTURE_ISS = "https://server.example.com";
  static final String FIXTURE_SUB = "123456";

  static void seedProvider() {
    tx(
        () -> {
          OidcProvider.delete("name", PROVIDER);
          OidcProvider provider = new OidcProvider();
          provider.name = PROVIDER;
          provider.issuerUrl = issuer();
          provider.clientId = "quarkus-app";
          provider.clientSecret = "secret";
          provider.scopes = List.of("openid", "profile", "email");
          provider.enabled = true;
          provider.persistAndFlush();
        });
  }

  static void cleanup() {
    tx(
        () -> {
          OidcProvider.delete("name", PROVIDER);
          User.delete("iss = ?1 and sub = ?2", FIXTURE_ISS, FIXTURE_SUB);
        });
  }

  static Cookie login() {
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

    var callbackRequest = given().redirects().follow(false).accept("text/html");
    stateCookies.forEach(callbackRequest::cookie);
    Response callback = callbackRequest.when().get(pathWithQuery(idp.getHeader("Location")));
    callback.then().statusCode(302);

    Cookie session =
        callback.getDetailedCookies().asList().stream()
            .filter(cookie -> cookie.getName().startsWith("q_session"))
            .findFirst()
            .orElse(null);
    assertNotNull(session, "session cookie set on callback");
    return session;
  }

  private static String issuer() {
    return ConfigProvider.getConfig().getValue("keycloak.url", String.class) + "/realms/quarkus";
  }

  private static String origin(String url) {
    URI uri = URI.create(url);
    String authority = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    return uri.getScheme() + "://" + authority;
  }

  private static String pathWithQuery(String url) {
    URI uri = URI.create(url);
    return uri.getQuery() == null ? uri.getPath() : uri.getPath() + "?" + uri.getRawQuery();
  }

  private static Map<String, String> queryParams(String url) {
    Map<String, String> params = new HashMap<>();
    String query = URI.create(url).getRawQuery();
    if (query == null) {
      return params;
    }
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0) {
        params.put(
            URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    return params;
  }

  private OidcConsoleSessionFixture() {}
}
