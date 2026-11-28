package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.vcs.VcsAuthFlow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Without a public URL, GitHub setup is disabled and leaves no auth-flow rows behind. */
@QuarkusTest
@TestProfile(GitHubIntegrationResourceTest.Profile.class)
class GitHubIntegrationResourceTest extends DatabaseTest {

  @Test
  void setupIsDisabledWhenPublicUrlIsUnset() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    given()
        .cookie(COOKIE, cookie)
        .get("/api/admin/vcs/github")
        .then()
        .statusCode(200)
        .body("setupEnabled", equalTo(false));
    given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body("{}")
        .post("/api/admin/vcs/github/manifest")
        .then()
        .statusCode(404);

    assertEquals(0, VcsAuthFlow.count());
  }

  public static final class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // An empty override unsets the test profile's http://statedb.test/.
      return Map.of(
          "statedb.local-glass.password-hash",
          io.quarkus.elytron.security.common.BcryptUtil.bcryptHash(PASSWORD),
          "statedb.oidc.bootstrap.name",
          "env-provider",
          "quarkus.arc.exclude-types",
          "app.statedb.persistence.bootstrap.OidcProviderBootstrap",
          "statedb.reports.public-base-url",
          "");
    }
  }
}
