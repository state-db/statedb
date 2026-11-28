package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static app.statedb.app.console.OidcConsoleSessionFixture.loginAs;
import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.StackRef;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Owner-or-admin HTTP contract for human organization membership management. */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class OrganizationMembersResourceTest extends DatabaseTest {
  private static final String ORG = "members-resource-org";
  private static final String OTHER_ORG = "members-resource-other-org";

  private UUID orgId;
  private UUID otherOrgId;

  @BeforeEach
  void seedOrganizations() {
    orgId = db.seedOrg(ORG);
    otherOrgId = db.seedOrg(OTHER_ORG);
  }

  @Test
  void ownerManagesHumansOnlyInTheirOrganization() {
    Cookie session = loginAs(ORG, OrgMember.Role.owner);
    UUID candidate = candidate("membership-candidate", "candidate@example.com");
    String path = "/api/orgs/" + ORG + "/members";

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(path + "/candidates")
        .then()
        .statusCode(200)
        .body("find { it.id == '" + candidate + "' }.email", is("candidate@example.com"));
    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("userId", candidate, "role", "guest"))
        .when()
        .post(path)
        .then()
        .statusCode(200)
        .body("role", is("guest"));
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(path)
        .then()
        .statusCode(200)
        .body("find { it.userId == '" + candidate + "' }.email", is("candidate@example.com"));
    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("userId", candidate, "role", "owner"))
        .when()
        .patch(path)
        .then()
        .statusCode(200)
        .body("email", is("candidate@example.com"))
        .body("role", is("owner"));
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/orgs/" + OTHER_ORG + "/members")
        .then()
        .statusCode(403);
  }

  @Test
  void finalOwnerCannotBeDemotedOrRemovedThroughTheApi() {
    Cookie session = loginAs(ORG, OrgMember.Role.owner);
    UUID ownerId = requiringNew().call(() -> OidcConsoleSessionFixture.fixtureUser().id);
    String path = "/api/orgs/" + ORG + "/members";

    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("userId", ownerId, "role", "member"))
        .when()
        .patch(path)
        .then()
        .statusCode(409);
    given()
        .cookie(session.getName(), session.getValue())
        .queryParam("userId", ownerId)
        .when()
        .delete(path)
        .then()
        .statusCode(409);
  }

  @Test
  void invalidMemberInputsRetainValidationMessages() {
    Cookie session = loginAs(ORG, OrgMember.Role.owner);
    UUID candidate = candidate("invalid-input-candidate", "invalid-input@example.com");
    String path = "/api/orgs/" + ORG + "/members";

    for (String body :
        List.of("{\"role\":\"guest\"}", "{\"userId\":\"not-a-uuid\",\"role\":\"guest\"}")) {
      given()
          .cookie(session.getName(), session.getValue())
          .contentType("application/json")
          .body(body)
          .when()
          .post(path)
          .then()
          .statusCode(400)
          .body(containsString("userId must be a UUID"));
    }

    for (String body :
        List.of(
            "{\"userId\":\"" + candidate + "\"}",
            "{\"userId\":\"" + candidate + "\",\"role\":\"administrator\"}")) {
      given()
          .cookie(session.getName(), session.getValue())
          .contentType("application/json")
          .body(body)
          .when()
          .post(path)
          .then()
          .statusCode(400)
          .body(containsString("invalid human role"));
    }
  }

  @Test
  void siteAdminManagesMembershipWithoutBelongingToOrganization() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    UUID userId = candidate("admin-added-user", "admin-added@example.com");
    UUID serviceUserId = candidate("admin-service-user", "service@example.com");

    given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body(Map.of("userId", userId, "role", "owner"))
        .when()
        .post("/api/orgs/" + orgId + "/members")
        .then()
        .statusCode(200)
        .body("role", is("owner"));
    requiringNew()
        .run(
            () -> {
              OrgMember service = new OrgMember();
              service.orgId = orgId;
              service.userId = serviceUserId;
              service.role = OrgMember.Role.service;
              service.persistAndFlush();
            });
    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/orgs/" + orgId + "/members")
        .then()
        .statusCode(200)
        .body("size()", is(1));
    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/orgs")
        .then()
        .statusCode(200)
        .body("find { it.id == '" + orgId + "' }.members", is(1));
  }

  @Test
  void listsAllGuestGrantsFromThisOrganizationOnly() {
    Cookie session = loginAs(ORG, OrgMember.Role.owner);
    UUID guestId = candidate("granted-guest", "granted-guest@example.com");
    String path = "/api/orgs/" + ORG + "/members";

    given()
        .cookie(session.getName(), session.getValue())
        .contentType("application/json")
        .body(Map.of("userId", guestId, "role", "guest"))
        .when()
        .post(path)
        .then()
        .statusCode(200)
        .body("grants.size()", is(0));

    StackRef app = db.seedStack(ORG, "apps", "prod");
    StackRef otherStack = db.seedStack(OTHER_ORG, "apps", "prod");
    requiringNew()
        .run(
            () -> {
              grant(orgId, guestId, app.stackId(), "StackReadResourcePolicy");
              grant(orgId, guestId, app.stackId(), "StackWriteResourcePolicy");
              grant(otherOrgId, guestId, otherStack.stackId(), "StackReadResourcePolicy");
            });

    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get(path)
        .then()
        .statusCode(200)
        .body("find { it.userId == '" + guestId + "' }.grants.size()", is(2))
        .body(
            "find { it.userId == '" + guestId + "' }.grants.project.unique()", is(List.of("apps")))
        .body("find { it.userId == '" + guestId + "' }.grants.stack.unique()", is(List.of("prod")));
  }

  private static UUID candidate(String sub, String email) {
    return requiringNew()
        .call(
            () -> {
              User user = new User();
              user.iss = "local:membership-resource";
              user.sub = sub;
              user.email = email;
              user.persistAndFlush();
              return user.id;
            });
  }

  private static void grant(UUID orgId, UUID userId, UUID stackId, String templateId) {
    PolicyGrant grant = new PolicyGrant();
    grant.orgId = orgId;
    grant.principalUserId = userId;
    grant.resourceStackId = stackId;
    grant.templateId = templateId;
    grant.persistAndFlush();
  }
}
