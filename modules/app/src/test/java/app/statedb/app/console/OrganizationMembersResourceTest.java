package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static app.statedb.app.console.OrganizationMembershipFixture.ORG;
import static app.statedb.app.console.OrganizationMembershipFixture.OTHER_ORG;
import static app.statedb.app.console.OrganizationMembershipFixture.candidate;
import static app.statedb.app.console.OrganizationMembershipFixture.ownerSession;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import app.statedb.persistence.stack.StackCreator;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Owner-or-admin HTTP contract for human organization membership management. */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class OrganizationMembersResourceTest {
  @Inject StackCreator stackCreator;

  @BeforeEach
  void seedOrganizations() {
    OrganizationMembershipFixture.seedOrganizations();
  }

  @AfterEach
  void cleanup() {
    OrganizationMembershipFixture.cleanup();
  }

  @Test
  void ownerManagesHumansOnlyInTheirOrganization() {
    Cookie session = ownerSession();
    UUID candidate = candidate("membership-candidate", "candidate@example.com");
    String path = "/api/orgs/" + ORG + "/members";

    givenAs(session)
        .when()
        .get(path + "/candidates")
        .then()
        .statusCode(200)
        .body("find { it.id == '" + candidate + "' }.email", is("candidate@example.com"));
    givenAs(session)
        .contentType("application/json")
        .body(Map.of("userId", candidate, "role", "guest"))
        .when()
        .post(path)
        .then()
        .statusCode(200)
        .body("role", is("guest"));
    givenAs(session)
        .when()
        .get(path)
        .then()
        .statusCode(200)
        .body("find { it.userId == '" + candidate + "' }.email", is("candidate@example.com"));
    givenAs(session)
        .contentType("application/json")
        .body(Map.of("userId", candidate, "role", "owner"))
        .when()
        .patch(path)
        .then()
        .statusCode(200)
        .body("email", is("candidate@example.com"))
        .body("role", is("owner"));
    givenAs(session).when().get("/api/orgs/" + OTHER_ORG + "/members").then().statusCode(403);
  }

  @Test
  void finalOwnerCannotBeDemotedOrRemovedThroughTheApi() {
    Cookie session = ownerSession();
    UUID ownerId = OrganizationMembershipFixture.fixtureUserId();
    String path = "/api/orgs/" + ORG + "/members";

    givenAs(session)
        .contentType("application/json")
        .body(Map.of("userId", ownerId, "role", "member"))
        .when()
        .patch(path)
        .then()
        .statusCode(409);
    givenAs(session).queryParam("userId", ownerId).when().delete(path).then().statusCode(409);
  }

  @Test
  void invalidMemberInputsRetainValidationMessages() {
    Cookie session = ownerSession();
    UUID candidate = candidate("invalid-input-candidate", "invalid-input@example.com");
    String path = "/api/orgs/" + ORG + "/members";

    for (String body :
        List.of("{\"role\":\"guest\"}", "{\"userId\":\"not-a-uuid\",\"role\":\"guest\"}")) {
      givenAs(session)
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
      givenAs(session)
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
    UUID orgId = OrganizationMembershipFixture.organizationId();
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
    OrganizationMembershipFixture.addServiceMember(orgId, serviceUserId);
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
    Cookie session = ownerSession();
    UUID guestId = candidate("granted-guest", "granted-guest@example.com");
    String path = "/api/orgs/" + ORG + "/members";

    givenAs(session)
        .contentType("application/json")
        .body(Map.of("userId", guestId, "role", "guest"))
        .when()
        .post(path)
        .then()
        .statusCode(200)
        .body("grants.size()", is(0));

    OrganizationMembershipFixture.grantAccessInBothOrganizations(stackCreator, guestId);

    givenAs(session)
        .when()
        .get(path)
        .then()
        .statusCode(200)
        .body("find { it.userId == '" + guestId + "' }.grants.size()", is(2))
        .body(
            "find { it.userId == '" + guestId + "' }.grants.project.unique()", is(List.of("apps")))
        .body("find { it.userId == '" + guestId + "' }.grants.stack.unique()", is(List.of("prod")));
  }

  private static RequestSpecification givenAs(Cookie session) {
    return given().cookie(session.getName(), session.getValue());
  }
}
