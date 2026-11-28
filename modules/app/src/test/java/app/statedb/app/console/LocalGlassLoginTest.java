package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.identity.OidcProvider;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Break-glass login end to end: the form POST to {@code /api/auth/local} mints an encrypted session
 * cookie that resolves to the bootstrap admin, and the gate closes — for new logins and existing
 * cookies alike — the moment an enabled OIDC provider exists. RestAssured connects over loopback,
 * so the origin check passes here; the non-loopback rejection is unit-tested in {@link
 * LocalGlassLoopbackTest}.
 */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class LocalGlassLoginTest extends DatabaseTest {

  private static final String PROVIDER = "glass-test-provider";

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
  void logoutExpiresBreakGlassCookieAndRedirectsToLogin() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    Response logout =
        given()
            .redirects()
            .follow(false)
            .cookie(COOKIE, cookie)
            .accept("text/html")
            .when()
            .get("/api/auth/logout");

    logout.then().statusCode(303);
    assertEquals("/console/login", URI.create(logout.getHeader("Location")).getPath());
    Cookie cleared =
        logout.getDetailedCookies().asList().stream()
            .filter(c -> c.getName().equals(COOKIE))
            .findFirst()
            .orElse(null);
    assertNotNull(cleared, "logout deletes the break-glass cookie");
    assertEquals(0, cleared.getMaxAge(), "break-glass cookie expired (Max-Age=0)");
    assertEquals("/", cleared.getPath(), "deletion targets the root cookie path");
  }

  @Test
  void wrongPasswordIs401WithoutCookie() {
    String cookie = login("not-the-password").statusCode(401).extract().cookie(COOKIE);
    assertNull(cookie);
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
  void adminCanForceUnlockAndQueueCompaction() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    UUID stackId = db.seedStack("ops-org", "ops-project", "ops-stack").stackId();
    TestDb.ActiveUpdate active = db.seedActiveUpdate(stackId, StackUpdate.Kind.UPDATE);
    String base = "/api/admin/stacks/ops-org/ops-project/ops-stack";

    given()
        .cookie(COOKIE, cookie)
        .queryParam("lockId", active.leaseId())
        .when()
        .delete(base + "/lock")
        .then()
        .statusCode(204);
    given().cookie(COOKIE, cookie).when().post(base + "/compact").then().statusCode(202);

    assertEquals(0L, StackLock.count("stackId", stackId));
    assertEquals(1L, CompactionJob.count("stackId", stackId));
  }

  @Test
  void adminManagesRuntimeOidcProvidersWithoutReadingSecrets() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    String name = "console-provider-test";
    String id =
        given()
            .cookie(COOKIE, cookie)
            .contentType("application/json")
            .body(
                Map.of(
                    "name",
                    name,
                    "issuerUrl",
                    "https://console-provider.invalid/",
                    "clientId",
                    "console-client",
                    "clientSecret",
                    "never-return-this",
                    "scopes",
                    List.of("openid", "email"),
                    "enabled",
                    false))
            .when()
            .post("/api/admin/providers")
            .then()
            .statusCode(200)
            .body("name", is(name))
            .body("issuerUrl", is("https://console-provider.invalid"))
            .body("clientSecret", nullValue())
            .extract()
            .path("id");

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/providers")
        .then()
        .statusCode(200)
        .body("find { it.id == '" + id + "' }.clientSecret", nullValue());

    given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body(Map.of("clientId", "rotated-client", "clientSecret", "rotated-secret"))
        .when()
        .patch("/api/admin/providers/" + id)
        .then()
        .statusCode(200)
        .body("clientId", is("rotated-client"))
        .body("clientSecret", nullValue());

    given()
        .cookie(COOKIE, cookie)
        .when()
        .delete("/api/admin/providers/" + id)
        .then()
        .statusCode(204);
    assertEquals(0L, OidcProvider.count("name", name));
  }

  @Test
  void adminCannotCreateProviderWithEnvManagedName() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body(
            Map.of(
                "name",
                "env-provider",
                "issuerUrl",
                "https://reserved-provider.invalid",
                "clientId",
                "reserved-client",
                "clientSecret",
                "reserved-secret"))
        .when()
        .post("/api/admin/providers")
        .then()
        .statusCode(403);

    assertEquals(0L, OidcProvider.count("name", "env-provider"));
  }

  @Test
  void adminCannotRenameProviderToEnvManagedName() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    String name = "console-rename-test";
    String id =
        given()
            .cookie(COOKIE, cookie)
            .contentType("application/json")
            .body(
                Map.of(
                    "name",
                    name,
                    "issuerUrl",
                    "https://console-rename.invalid",
                    "clientId",
                    "rename-client",
                    "clientSecret",
                    "rename-secret"))
            .when()
            .post("/api/admin/providers")
            .then()
            .statusCode(200)
            .body("enabled", is(false))
            .extract()
            .path("id");

    given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body(Map.of("name", "env-provider"))
        .when()
        .patch("/api/admin/providers/" + id)
        .then()
        .statusCode(403);

    assertEquals(0L, OidcProvider.count("name", "env-provider"));
  }

  @Test
  void enabledProviderClosesGateAndKillsExistingSessions() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    requiringNew()
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

    login(PASSWORD).statusCode(401);
    given().cookie(COOKIE, cookie).when().get("/api/user").then().statusCode(401);
    given().when().get("/api/auth/providers").then().statusCode(200).body("breakGlass", is(false));
  }
}
