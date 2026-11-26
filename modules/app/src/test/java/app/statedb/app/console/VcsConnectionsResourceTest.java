package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.testsupport.InMemoryGitHubAppManager;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(OidcWiremockTestResource.class)
class VcsConnectionsResourceTest {

  private static final String ORG = "vcs-console-org";
  private static final String PATH = "/api/orgs/" + ORG + "/vcs";

  @Inject InMemoryGitHubAppManager github;
  @Inject app.statedb.persistence.vcs.VcsAppRegistry registrations;

  @BeforeEach
  void seed() {
    github.reset();
    github.completeManifest("test");
    registrations.replace(
        new app.statedb.persistence.vcs.VcsAppRegistry.AppRegistration(
            "github.com",
            12345,
            "state-db-test",
            "state-db",
            "https://github.test/apps/state-db-test",
            "test-private-key",
            "test-client",
            "test-secret"));
    OidcConsoleSessionFixture.seedProvider();
    tx(
        () -> {
          Organization org = new Organization();
          org.name = ORG;
          org.displayName = "VCS Console Test";
          org.persistAndFlush();
        });
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          Organization.delete("name", ORG);
          app.statedb.persistence.vcs.VcsAppRegistration.deleteAll();
          User.delete("iss", "local:service");
        });
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void ownerCompletesVerifiedRoundTripAndCanDisconnect() {
    Cookie session = establishSession(OrgMember.Role.owner);
    String state = begin(session);
    String oauth = install(session, state, 9876);
    String routed =
        given()
            .cookie(session)
            .redirects()
            .follow(false)
            .queryParam("state", oauth)
            .queryParam("code", "valid-code")
            .get("/api/vcs/github/callback")
            .then()
            .statusCode(303)
            .extract()
            .header("Location");
    assertTrue(routed.startsWith("http://statedb.test/api/orgs/"));
    java.net.URI callback = java.net.URI.create(routed);
    given()
        .cookie(session)
        .redirects()
        .follow(false)
        .get(callback.getRawPath() + "?" + callback.getRawQuery())
        .then()
        .statusCode(303)
        .header("Location", org.hamcrest.Matchers.containsString("github=connected"))
        .header("Location", org.hamcrest.Matchers.containsString("org=" + ORG));
    given()
        .cookie(session)
        .get(PATH + "/connections")
        .then()
        .statusCode(200)
        .body("[0].installationId", org.hamcrest.Matchers.equalTo(9876))
        .body("[0].accountLogin", org.hamcrest.Matchers.equalTo("state-db"))
        .body("[0].repositorySelection", org.hamcrest.Matchers.equalTo("selected"));
    // The callback cannot be replayed.
    given()
        .cookie(session)
        .queryParam("state", oauth)
        .queryParam("code", "valid-code")
        .get(PATH + "/callback")
        .then()
        .statusCode(400);
    given().cookie(session).delete(PATH + "/connections").then().statusCode(204);
    given()
        .cookie(session)
        .get(PATH + "/connections")
        .then()
        .body("[0].enabled", org.hamcrest.Matchers.equalTo(false));
  }

  @Test
  void installationApprovalWithoutIdReturnsPendingNotice() {
    Cookie session = establishSession(OrgMember.Role.owner);
    String routed =
        given()
            .cookie(session)
            .redirects()
            .follow(false)
            .queryParam("state", begin(session))
            .get("/api/vcs/github/setup")
            .then()
            .statusCode(303)
            .extract()
            .header("Location");
    java.net.URI target = java.net.URI.create(routed);
    given()
        .cookie(session)
        .redirects()
        .follow(false)
        .get(target.getRawPath() + "?" + target.getRawQuery())
        .then()
        .statusCode(303)
        .header("Location", org.hamcrest.Matchers.containsString("github=installation-pending"));
    assertEquals(0, connectionCount());
  }

  @Test
  void arbitraryInstallationIdCannotConnectEvenWithValidState() {
    Cookie session = establishSession(OrgMember.Role.owner);
    String oauth = install(session, begin(session), 5555);
    given()
        .cookie(session)
        .redirects()
        .follow(false)
        .queryParam("state", oauth)
        .queryParam("code", "valid-code")
        .get(PATH + "/callback")
        .then()
        .statusCode(303)
        .header("Location", org.hamcrest.Matchers.containsString("github=verification-failed"));
    assertEquals(0, connectionCount());
  }

  @Test
  void membershipIsRecheckedAfterReturningFromGithub() {
    Cookie session = establishSession(OrgMember.Role.owner);
    String oauth = install(session, begin(session), 9876);
    demoteOwnerToMember();
    given()
        .cookie(session)
        .queryParam("state", oauth)
        .queryParam("code", "valid-code")
        .get(PATH + "/callback")
        .then()
        .statusCode(403);
    assertEquals(0, connectionCount());
  }

  @Test
  void forgedStateAndWrongCallbackPurposeAreRejected() {
    Cookie session = establishSession(OrgMember.Role.owner);
    given()
        .cookie(session)
        .queryParam("state", "forged")
        .queryParam("installation_id", 9876)
        .get(PATH + "/setup")
        .then()
        .statusCode(400);
    given()
        .cookie(session)
        .queryParam("state", begin(session))
        .queryParam("code", "valid-code")
        .get(PATH + "/callback")
        .then()
        .statusCode(400);
  }

  @Test
  void disconnectInvalidatesPendingHandshakes() {
    Cookie session = establishSession(OrgMember.Role.owner);
    String oauth = install(session, begin(session), 9876);
    given().cookie(session).delete(PATH + "/connections").then().statusCode(204);
    given()
        .cookie(session)
        .queryParam("state", oauth)
        .queryParam("code", "valid-code")
        .get(PATH + "/callback")
        .then()
        .statusCode(400);
  }

  @Test
  void removedInstallationPickerAndDirectConnectCannotBypassVerification() {
    Cookie session = establishSession(OrgMember.Role.owner);
    given().cookie(session).get(PATH + "/installations").then().statusCode(404);
    given()
        .cookie(session)
        .contentType("application/json")
        .body(Map.of("installationId", 9876))
        .post(PATH + "/connections")
        .then()
        .statusCode(405);
  }

  @Test
  void memberCannotManageVcsConnection() {
    Cookie session = establishSession(OrgMember.Role.member);
    given().cookie(session).post(PATH + "/connect").then().statusCode(403);
    given().cookie(session).delete(PATH + "/connections").then().statusCode(403);
  }

  private long connectionCount() {
    return app.statedb.persistence.vcs.VcsConnection.count(
        "orgId", Organization.<Organization>find("name", ORG).firstResult().id);
  }

  private String begin(Cookie session) {
    String url =
        given()
            .cookie(session)
            .post(PATH + "/connect")
            .then()
            .statusCode(200)
            .extract()
            .path("url");
    return state(url);
  }

  private String install(Cookie session, String state, long installation) {
    // Exercise the fixed callback router, then the org-authorized installation endpoint.
    String routed =
        given()
            .cookie(session)
            .redirects()
            .follow(false)
            .queryParam("state", state)
            .queryParam("installation_id", installation)
            .get("/api/vcs/github/setup")
            .then()
            .statusCode(303)
            .extract()
            .header("Location");
    assertTrue(routed.startsWith("http://statedb.test/api/orgs/"));
    java.net.URI uri = java.net.URI.create(routed);
    String authorization =
        given()
            .cookie(session)
            .redirects()
            .follow(false)
            .get(uri.getRawPath() + "?" + uri.getRawQuery())
            .then()
            .statusCode(303)
            .extract()
            .header("Location");
    return state(authorization);
  }

  private static String state(String url) {
    return java.net.URI.create(url).getQuery().split("state=")[1].split("&")[0];
  }

  private Cookie establishSession(OrgMember.Role role) {
    Cookie session = OidcConsoleSessionFixture.login();
    tx(
        () -> {
          User user =
              User.<User>find(
                      "iss = ?1 and sub = ?2",
                      OidcConsoleSessionFixture.FIXTURE_ISS,
                      OidcConsoleSessionFixture.FIXTURE_SUB)
                  .firstResult();
          assertNotNull(user);
          Organization org = Organization.<Organization>find("name", ORG).firstResult();
          OrgMember member = new OrgMember();
          member.orgId = org.id;
          member.userId = user.id;
          member.role = role;
          member.persistAndFlush();
        });
    return session;
  }

  private void demoteOwnerToMember() {
    tx(
        () ->
            OrgMember.update(
                "role = ?1 where orgId = ?2",
                OrgMember.Role.member,
                Organization.<Organization>find("name", ORG).firstResult().id));
  }
}
