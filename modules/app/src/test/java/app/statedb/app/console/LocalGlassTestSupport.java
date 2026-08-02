package app.statedb.app.console;

import static io.restassured.RestAssured.given;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.ValidatableResponse;
import java.util.Map;

final class LocalGlassTestSupport {
  static final String PASSWORD = "glass-recovery-pw";
  static final String COOKIE = "state-db-glass";

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Keep the break-glass gate open by preventing bootstrap from provisioning an enabled
      // provider. Resource tests can still seed a provider explicitly when they need OIDC.
      return Map.of(
          "state-db.local-glass.password-hash",
          BcryptUtil.bcryptHash(PASSWORD),
          "state-db.oidc.bootstrap.name",
          "env-provider",
          "quarkus.arc.exclude-types",
          "app.statedb.persistence.bootstrap.OidcProviderBootstrap");
    }
  }

  static ValidatableResponse login(String password) {
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

  private LocalGlassTestSupport() {}
}
