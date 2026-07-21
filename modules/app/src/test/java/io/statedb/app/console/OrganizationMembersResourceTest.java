package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static io.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static io.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static io.statedb.app.console.LocalGlassTestSupport.login;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
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

  @BeforeEach
  void seedOrganizations() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization.delete("name in ?1", List.of(ORG, OTHER_ORG));
              User.delete("iss", "local:membership-resource");
            });
    OidcConsoleSessionFixture.cleanup();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              persistOrganization(ORG);
              persistOrganization(OTHER_ORG);
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
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
  void siteAdminManagesMembershipWithoutBelongingToOrganization() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    UUID orgId =
        QuarkusTransaction.requiringNew()
            .call(() -> Organization.<Organization>find("name", ORG).firstResult().id);
    UUID userId = candidate("admin-added-user", "admin-added@example.com");

    given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body(Map.of("userId", userId, "role", "owner"))
        .when()
        .post("/api/orgs/" + orgId + "/members")
        .then()
        .statusCode(200)
        .body("role", is("owner"));
    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/orgs/" + orgId + "/members")
        .then()
        .statusCode(200)
        .body("size()", is(1));
  }

  private static Cookie ownerSession() {
    OidcConsoleSessionFixture.seedProvider();
    Cookie session = OidcConsoleSessionFixture.login();
    QuarkusTransaction.requiringNew()
        .run(
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
    return QuarkusTransaction.requiringNew().call(() -> fixtureUser().id);
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
    return QuarkusTransaction.requiringNew()
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

  private static void persistOrganization(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    org.persistAndFlush();
  }
}
