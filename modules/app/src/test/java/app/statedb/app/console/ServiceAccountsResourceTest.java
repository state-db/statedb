package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

  private static final String ORG = "svc-console-org";
  private static final String SA_PATH = "/api/orgs/" + ORG + "/service-accounts";

  @BeforeEach
  void seedProvider() {
    OidcConsoleSessionFixture.seedProvider();
    tx(
        () -> {
          Organization org = new Organization();
          org.name = ORG;
          org.displayName = "Service Console Test";
          org.persistAndFlush();
        });
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          Organization.delete("name", ORG);
          User.delete("iss", "local:service");
        });
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void ownerMintsListsAndRevokes() {
    Cookie session = establishSession(OrgMember.Role.owner);

    // Mint: the raw token appears exactly once, in this response.
    Response created =
        given()
            .cookie(session.getName(), session.getValue())
            .contentType("application/json")
            .body(
                Map.of(
                    "name",
                    "GitHub Actions deploy",
                    "expiresInDays",
                    90,
                    "accessProfile",
                    "stack-lifecycle"))
            .when()
            .post(SA_PATH);
    created.then().statusCode(200);
    String accountId = created.path("id");
    String token = created.path("token");
    assertNotNull(token, "create response carries the raw token");
    created.then().body("accessProfile", org.hamcrest.Matchers.equalTo("stack-lifecycle"));

    // The minted token drives the Pulumi surface.
    given()
        .header("Authorization", "token " + token)
        .when()
        .get("/pulumi/api/user")
        .then()
        .statusCode(200)
        .body("githubLogin", org.hamcrest.Matchers.equalTo("GitHub Actions deploy"));

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(SA_PATH)
        .then()
        .statusCode(200)
        .body("size()", org.hamcrest.Matchers.is(1))
        .body("[0].id", org.hamcrest.Matchers.equalTo(accountId))
        .body("[0].name", org.hamcrest.Matchers.equalTo("GitHub Actions deploy"))
        .body("[0].accessProfile", org.hamcrest.Matchers.equalTo("stack-lifecycle"))
        .body("[0].token", org.hamcrest.Matchers.nullValue())
        .body("[0].createdByName", org.hamcrest.Matchers.equalTo("Alice Example"));

    // Revoke: 204, the token dies, and repeated revocation is idempotent.
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
        .statusCode(204);

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/service-accounts?status=inactive")
        .then()
        .statusCode(200)
        .body("[0].id", org.hamcrest.Matchers.equalTo(accountId))
        .body("[0].tokenRevokedAt", org.hamcrest.Matchers.notNullValue())
        .body("[0].accountDeactivatedAt", org.hamcrest.Matchers.notNullValue())
        .body("[0].revokedByName", org.hamcrest.Matchers.equalTo("Alice Example"));
  }

  @Test
  void nonOwnerCannotManageServiceAccounts() {
    Cookie session = establishSession(OrgMember.Role.member);

    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("name", "sneaky", "expiresInDays", 90))
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

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/service-accounts")
        .then()
        .statusCode(403);
  }

  @Test
  void organizationOwnerCannotUseSiteAdministrationActions() {
    Cookie session = establishSession(OrgMember.Role.owner);

    for (String path :
        List.of(
            "/api/admin/providers", "/api/admin/orgs", "/api/admin/users", "/api/admin/locks")) {
      given().cookie(session.getName(), session.getValue()).when().get(path).then().statusCode(403);
    }
  }

  @Test
  void mintRequiresOneOfTheSupportedExpiries() {
    Cookie session = establishSession(OrgMember.Role.owner);

    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("name", "unsupported", "expiresInDays", 7))
        .when()
        .post(SA_PATH)
        .then()
        .statusCode(400);

    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("name", "missing expiry"))
        .when()
        .post(SA_PATH)
        .then()
        .statusCode(400);
  }

  @Test
  void omittedProfileDefaultsToDeployAndDuplicateActiveNamesAreAllowed() {
    Cookie session = establishSession(OrgMember.Role.owner);
    Map<String, Object> request = Map.of("name", "rotation-name", "expiresInDays", 30);

    for (int i = 0; i < 2; i++) {
      given()
          .cookie(session.getName(), session.getValue())
          .contentType("application/json")
          .body(request)
          .when()
          .post(SA_PATH)
          .then()
          .statusCode(200)
          .body("accessProfile", org.hamcrest.Matchers.equalTo("deploy"));
    }

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(SA_PATH)
        .then()
        .statusCode(200)
        .body("size()", org.hamcrest.Matchers.is(2))
        .body(
            "name",
            org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("rotation-name")));
  }

  @Test
  void anonymousIsChallengedNotServed() {
    given()
        .contentType("application/json")
        .body(Map.of("name", "nope", "expiresInDays", 90))
        .when()
        .post(SA_PATH)
        .then()
        .statusCode(401);
  }

  @Test
  void deactivatedHumanSessionCannotManageAccounts() {
    Cookie session = establishSession(OrgMember.Role.owner);
    deactivateFixtureUser();

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/service-accounts")
        .then()
        .statusCode(401);
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/user")
        .then()
        .statusCode(401);
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/admin/users")
        .then()
        .statusCode(401);
  }

  // Drives the console code flow to a usable session cookie, then grants the fixture user the
  // requested role in the test org. Mirrors CliLoginFlowTest.
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
          assertNotNull(user, "callback provisions the user");
          user.displayName = "Alice Example";
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

  private void deactivateFixtureUser() {
    tx(
        () ->
            User.update(
                "deactivatedAt = ?1 where iss = ?2 and sub = ?3",
                Instant.now(),
                OidcConsoleSessionFixture.FIXTURE_ISS,
                OidcConsoleSessionFixture.FIXTURE_SUB));
  }
}
