package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.identity.OidcProvider;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.CompactionJob;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
class LocalGlassLoginTest {

  private static final String PROVIDER = "glass-test-provider";

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
  void logoutExpiresBreakGlassCookieAndRedirectsToLogin() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    io.restassured.response.Response logout =
        given()
            .redirects()
            .follow(false)
            .cookie(COOKIE, cookie)
            .accept("text/html")
            .when()
            .get("/api/auth/logout");

    logout.then().statusCode(303);
    assertEquals("/console/login", java.net.URI.create(logout.getHeader("Location")).getPath());
    io.restassured.http.Cookie cleared =
        logout.getDetailedCookies().asList().stream()
            .filter(c -> c.getName().equals(COOKIE))
            .findFirst()
            .orElse(null);
    org.junit.jupiter.api.Assertions.assertNotNull(
        cleared, "logout deletes the break-glass cookie");
    assertEquals(0, cleared.getMaxAge(), "break-glass cookie expired (Max-Age=0)");
    assertEquals("/", cleared.getPath(), "deletion targets the root cookie path");
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
  void adminCanForceUnlockAndQueueCompaction() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    UUID stackId = seedOperationalStack();
    UUID lockId = read(() -> StackLock.<StackLock>find("stackId", stackId).singleResult().id);
    String base = "/api/admin/stacks/ops-org/ops-project/ops-stack";

    given()
        .cookie(COOKIE, cookie)
        .queryParam("lockId", lockId)
        .when()
        .delete(base + "/lock")
        .then()
        .statusCode(204);
    given().cookie(COOKIE, cookie).when().post(base + "/compact").then().statusCode(202);

    assertUnlockedAndQueued(stackId);

    deleteOperationsOrganization();
  }

  @Test
  void adminManagesRuntimeOidcProvidersWithoutReadingSecrets() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    String name = "console-provider-test";
    try {
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
      assertEquals(0L, read(() -> OidcProvider.count("name", name)));
    } finally {
      deleteProvider(name);
    }
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

    assertEquals(0L, read(() -> OidcProvider.count("name", "env-provider")));
  }

  @Test
  void adminCannotRenameProviderToEnvManagedName() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    String name = "console-rename-test";
    try {
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

      assertEquals(0L, read(() -> OidcProvider.count("name", "env-provider")));
    } finally {
      deleteProvider(name);
    }
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

  private void seedEnabledProvider() {
    tx(
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
    tx(
        () ->
            em.createNativeQuery("DELETE FROM oidc_providers WHERE name = ?1")
                .setParameter(1, PROVIDER)
                .executeUpdate());
  }

  private UUID seedOperationalStack() {
    return read(
        () -> {
          Organization org = new Organization();
          org.name = "ops-org";
          org.displayName = "Operations test";
          org.persist();

          Project project = new Project();
          project.orgId = org.id;
          project.name = "ops-project";
          project.persist();

          Workspace workspace = new Workspace();
          workspace.orgId = org.id;
          workspace.kind = Workspace.Kind.pulumi;
          workspace.persist();

          Stack stack = new Stack();
          stack.orgId = org.id;
          stack.projectId = project.id;
          stack.workspaceId = workspace.id;
          stack.name = "ops-stack";
          stack.persist();

          StackState state = new StackState();
          state.stackId = stack.id;
          state.persist();

          Instant now = Instant.now();
          StackUpdate update = new StackUpdate();
          update.stackId = stack.id;
          update.version = 1;
          update.dryRun = false;
          update.kind = StackUpdate.Kind.UPDATE;
          update.status = StackUpdate.Status.IN_PROGRESS;
          update.startedAt = now;
          update.persist();
          stack.activeUpdateId = update.id;

          StackLock lock = new StackLock();
          lock.stackId = stack.id;
          lock.updateId = update.id;
          lock.lockedBy = "stuck-update";
          lock.lockTokenHash = UUID.randomUUID().toString().getBytes();
          lock.lockedAt = now;
          lock.heartbeatAt = now;
          lock.expiresAt = now.plusSeconds(300);
          lock.maxExpiresAt = now.plusSeconds(600);
          lock.persist();
          return stack.id;
        });
  }

  private void deleteOperationsOrganization() {
    tx(() -> Organization.delete("name", "ops-org"));
  }

  private void assertUnlockedAndQueued(UUID stackId) {
    tx(
        () -> {
          assertEquals(0L, StackLock.count("stackId", stackId));
          assertEquals(1L, CompactionJob.count("stackId", stackId));
        });
  }

  private void deleteProvider(String name) {
    tx(() -> OidcProvider.delete("name", name));
  }
}
