package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
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
  private static final String ORG = "members-resource-org";
  private static final String OTHER_ORG = "members-resource-other-org";

  @Inject StackCreator stackCreator;

  @BeforeEach
  void seedOrganizations() {
    tx(
        () -> {
          Organization.delete("name in ?1", List.of(ORG, OTHER_ORG));
          User.delete("iss", "local:membership-resource");
        });
    OidcConsoleSessionFixture.cleanup();
    tx(
        () -> {
          persistOrganization(ORG);
          persistOrganization(OTHER_ORG);
        });
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          Organization.delete("name in ?1", List.of(ORG, OTHER_ORG));
          User.delete("iss", "local:membership-resource");
        });
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void ownerManagesHumansOnlyInTheirOrganization() {
    Cookie session = ownerSession();
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
    Cookie session = ownerSession();
    UUID ownerId = fixtureUserId();
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
    Cookie session = ownerSession();
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
    UUID orgId = read(() -> Organization.<Organization>find("name", ORG).firstResult().id);
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
    tx(
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
    Cookie session = ownerSession();
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

    tx(
        () -> {
          Organization org = Organization.<Organization>find("name", ORG).firstResult();
          Organization other = Organization.<Organization>find("name", OTHER_ORG).firstResult();
          StackRef app = stackCreator.create(org.id, org.name, "apps", "prod", Map.of());
          StackRef otherStack = stackCreator.create(other.id, other.name, "apps", "prod", Map.of());
          grant(org.id, guestId, app.stackId(), "StackReadResourcePolicy");
          grant(org.id, guestId, app.stackId(), "StackWriteResourcePolicy");
          grant(other.id, guestId, otherStack.stackId(), "StackReadResourcePolicy");
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

  private static Cookie ownerSession() {
    OidcConsoleSessionFixture.seedProvider();
    Cookie session = OidcConsoleSessionFixture.login();
    tx(
        () -> {
          User user = fixtureUser();
          Organization org = Organization.<Organization>find("name", ORG).firstResult();
          OrgMember membership = new OrgMember();
          membership.orgId = org.id;
          membership.userId = user.id;
          membership.role = OrgMember.Role.owner;
          membership.persistAndFlush();
        });
    return session;
  }

  private static UUID fixtureUserId() {
    return read(() -> fixtureUser().id);
  }

  private static User fixtureUser() {
    User user =
        User.<User>find(
                "iss = ?1 and sub = ?2",
                OidcConsoleSessionFixture.FIXTURE_ISS,
                OidcConsoleSessionFixture.FIXTURE_SUB)
            .firstResult();
    assertNotNull(user, "OIDC callback provisions the fixture user");
    return user;
  }

  private static UUID candidate(String sub, String email) {
    return read(
        () -> {
          User user = new User();
          user.iss = "local:membership-resource";
          user.sub = sub;
          user.email = email;
          user.persistAndFlush();
          return user.id;
        });
  }

  private static void persistOrganization(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    org.persistAndFlush();
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
