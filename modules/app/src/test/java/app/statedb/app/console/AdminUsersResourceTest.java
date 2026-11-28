package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.ServiceAccountCreator;
import app.statedb.persistence.identity.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HTTP contract for the site-admin users audit view: identity, org role, and the service split. */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class AdminUsersResourceTest extends DatabaseTest {
  private static final String HUMAN_ISS = "https://accounts.google.com";
  private static final String HUMAN_SUB = "s12-user-115";
  private static final String ORG = "s12-users-test-org";

  @Inject ServiceAccountCreator serviceAccounts;

  @Test
  void listsHumansAndServiceAccountsWithIdentityAndRole() {
    UUID[] seeded =
        requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG;
                  org.displayName = "S12 Users Test";
                  org.persist();

                  User human = new User();
                  human.iss = HUMAN_ISS;
                  human.sub = HUMAN_SUB;
                  human.email = "s12@example.com";
                  human.displayName = "S12 Human";
                  human.lastLoginAt = Instant.now();
                  human.persist();

                  OrgMember membership = new OrgMember();
                  membership.orgId = org.id;
                  membership.userId = human.id;
                  membership.role = OrgMember.Role.member;
                  membership.persist();
                  return new UUID[] {org.id, human.id};
                });
    UUID orgId = seeded[0];
    UUID humanId = seeded[1];

    // ServiceAccountCreator manages its own transaction and mints a users row + service membership.
    UUID serviceId = serviceAccounts.create(orgId, "s12-ci-bot", 30, humanId).accountId();
    requiringNew()
        .run(
            () -> {
              ApiToken current =
                  ApiToken.<ApiToken>find(
                          "userId = ?1 and kind = ?2", serviceId, ApiToken.Kind.service_account)
                      .singleResult();
              ApiToken rotated = new ApiToken();
              rotated.tokenHash = "sha256:" + "b".repeat(64);
              rotated.description = "s12-ci-bot rotated";
              rotated.userId = current.userId;
              rotated.orgId = current.orgId;
              rotated.kind = ApiToken.Kind.service_account;
              rotated.expiresAt = Instant.now().plusSeconds(60 * 60);
              rotated.persistAndFlush();
            });

    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    String human = humanId.toString();
    String service = serviceId.toString();

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/users?includeService=true")
        .then()
        .statusCode(200)
        .body("page", is(1))
        .body("pageSize", is(50))
        // The freshly-signed-in human: canonical <iss>:<sub> and their org role.
        .body("users.find { it.id == '" + human + "' }.iss", is(HUMAN_ISS))
        .body("users.find { it.id == '" + human + "' }.sub", is(HUMAN_SUB))
        .body("users.find { it.id == '" + human + "' }.principalType", is("human"))
        .body("users.find { it.id == '" + human + "' }.siteAdmin", is(false))
        .body("users.find { it.id == '" + human + "' }.organizations[0].name", is(ORG))
        .body("users.find { it.id == '" + human + "' }.organizations[0].role", is("member"))
        // The service account: discriminated, reserved issuer, never logged in.
        .body(
            "users.find { it.id == '" + service + "' }.iss", is(ServiceAccountCreator.SERVICE_ISS))
        .body("users.find { it.id == '" + service + "' }.principalType", is("service"))
        .body("users.find { it.id == '" + service + "' }.credentialStatus", is("active"))
        .body("users.find { it.id == '" + service + "' }.lastLoginAt", nullValue())
        .body("users.find { it.id == '" + service + "' }.organizations[0].role", is("service"));

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/users")
        .then()
        .statusCode(200)
        .body("users.find { it.id == '" + human + "' }.id", is(human))
        .body("users.find { it.id == '" + service + "' }", nullValue());
  }
}
