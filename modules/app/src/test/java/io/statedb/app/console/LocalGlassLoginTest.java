package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static io.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static io.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static io.statedb.app.console.LocalGlassTestSupport.login;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.statedb.persistence.identity.OidcProvider;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.stack.CompactionJob;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.Workspace;
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
    String base = "/api/admin/stacks/ops-org/ops-project/ops-stack";

    given().cookie(COOKIE, cookie).when().delete(base + "/lock").then().statusCode(204);
    given().cookie(COOKIE, cookie).when().post(base + "/compact").then().statusCode(202);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(0L, StackLock.count("stackId", stackId));
              assertEquals(1L, CompactionJob.count("stackId", stackId));
            });

    QuarkusTransaction.requiringNew().run(() -> Organization.delete("name", "ops-org"));
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
      assertEquals(
          0L, QuarkusTransaction.requiringNew().call(() -> OidcProvider.count("name", name)));
    } finally {
      QuarkusTransaction.requiringNew().run(() -> OidcProvider.delete("name", name));
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

    assertEquals(
        0L,
        QuarkusTransaction.requiringNew().call(() -> OidcProvider.count("name", "env-provider")));
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

      assertEquals(
          0L,
          QuarkusTransaction.requiringNew().call(() -> OidcProvider.count("name", "env-provider")));
    } finally {
      QuarkusTransaction.requiringNew().run(() -> OidcProvider.delete("name", name));
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

  private UUID seedOperationalStack() {
    return QuarkusTransaction.requiringNew()
        .call(
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
              StackLock lock = new StackLock();
              lock.stackId = stack.id;
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
}
