package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.statedb.persistence.identity.OidcProvider;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Break-glass login end to end: the form POST to {@code /api/auth/local} mints an encrypted session
 * cookie that resolves to the bootstrap admin, and the gate closes — for new logins and existing
 * cookies alike — the moment an enabled OIDC provider exists. RestAssured connects over loopback,
 * so the origin check passes here; the non-loopback rejection is unit-tested in {@link
 * LocalGlassLoopbackTest}.
 */
@QuarkusTest
@TestProfile(LocalGlassLoginTest.Profile.class)
class LocalGlassLoginTest {

  static final String PASSWORD = "glass-recovery-pw";
  static final String COOKIE = "state-db-glass";
  private static final String PROVIDER = "glass-test-provider";

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("state-db.local-glass.password-hash", BcryptUtil.bcryptHash(PASSWORD));
    }
  }

  @Inject EntityManager em;

  @Test
  void loginMintsAdminSession() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/user")
        .then()
        .statusCode(200)
        .body("siteAdmin", is(true))
        .body("organizations", empty());
  }

  @Test
  void wrongPasswordIs401WithoutCookie() {
    String cookie = login("not-the-password").statusCode(401).extract().cookie(COOKIE);
    org.junit.jupiter.api.Assertions.assertNull(cookie);
  }

  @Test
  void providersEndpointAdvertisesBreakGlass() {
    given()
        .when()
        .get("/api/auth/providers")
        .then()
        .statusCode(200)
        .body("providers", empty())
        .body("breakGlass", is(true));
  }

  @Test
  void enabledProviderClosesGateAndKillsExistingSessions() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    seedEnabledProvider();
    try {
      login(PASSWORD).statusCode(401);
      given().cookie(COOKIE, cookie).when().get("/api/user").then().statusCode(401);
      given()
          .when()
          .get("/api/auth/providers")
          .then()
          .statusCode(200)
          .body("breakGlass", is(false));
    } finally {
      deleteProvider();
    }
  }

  private ValidatableResponse login(String password) {
    return given()
        .contentType("application/x-www-form-urlencoded")
        .formParam("username", "admin")
        .formParam("password", password)
        .redirects()
        .follow(false)
        .when()
        .post("/api/auth/local")
        .then();
  }

  private void seedEnabledProvider() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OidcProvider p = new OidcProvider();
              p.name = PROVIDER;
              p.issuerUrl = "https://glass-test.invalid";
              p.clientId = "cid";
              p.clientSecret = "sec";
              p.scopes = List.of("openid");
              p.enabled = true;
              p.persist();
            });
  }

  private void deleteProvider() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM oidc_providers WHERE name = ?1")
                    .setParameter(1, PROVIDER)
                    .executeUpdate());
  }
}
