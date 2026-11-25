package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Site admins can inventory and mint credentials across orgs without holding memberships. */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class ServiceAccountInventoryAdminTest {

  private static final List<String> ORGS = List.of("svc-admin-a", "svc-admin-b");

  @BeforeEach
  void seedOrganizations() {
    tx(
        () ->
            ORGS.forEach(
                name -> {
                  Organization org = new Organization();
                  org.name = name;
                  org.displayName = name.toUpperCase();
                  org.persist();
                }));
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          Organization.delete("name in ?1", ORGS);
          User.delete("iss", "local:service");
        });
  }

  @Test
  void listsEveryOrganizationsServiceAccounts() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    for (String org : ORGS) {
      given()
          .cookie(COOKIE, cookie)
          .contentType("application/json")
          .body(Map.of("name", org + "-deploy", "expiresInDays", 90))
          .when()
          .post("/api/orgs/" + org + "/service-accounts")
          .then()
          .statusCode(200);
    }

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/service-accounts?status=active")
        .then()
        .statusCode(200)
        .body("size()", is(2))
        .body("org", containsInAnyOrder(ORGS.toArray()));
  }
}
