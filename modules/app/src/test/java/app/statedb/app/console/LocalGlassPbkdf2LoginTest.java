package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The managed-installer credential path end to end: a stack that generated its own password and
 * injected only the PBKDF2 verifier must still produce a working admin session.
 *
 * <p>{@link LocalGlassLoginTest} covers the same flow for a bcrypt hash. This exists separately
 * because the AWS template's first-run access depends on the PBKDF2 branch specifically — if it
 * regressed, every fresh Marketplace stack would be unopenable and no bcrypt test would notice.
 */
@QuarkusTest
@TestProfile(LocalGlassPbkdf2LoginTest.Profile.class)
class LocalGlassPbkdf2LoginTest {

  /** As produced by the bootstrap Lambda in {@code deploy/aws/cloudformation.yaml}. */
  private static final String PBKDF2_HASH =
      "$pbkdf2-sha256$600000$c3RhdGVkYi1maXh0dXJlMA==$p/OYbkEBUe4q5qlL9mh8CCCbJJ95ZRGFIiAJlE7qQFo=";

  private static final String PASSWORD = "glass-recovery-pw";

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "statedb.local-glass.password-hash",
          PBKDF2_HASH,
          "statedb.oidc.bootstrap.name",
          "env-provider",
          "quarkus.arc.exclude-types",
          "app.statedb.persistence.bootstrap.OidcProviderBootstrap");
    }
  }

  @Test
  void pbkdf2LoginMintsAdminSession() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/user")
        .then()
        .statusCode(200)
        .body("siteAdmin", is(true));
  }

  @Test
  void pbkdf2LoginRejectsTheWrongPassword() {
    login("glass-recovery-pX").statusCode(401);
  }
}
