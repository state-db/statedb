package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static io.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static io.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static io.statedb.app.console.LocalGlassTestSupport.login;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
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
    QuarkusTransaction.requiringNew()
        .run(
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
    QuarkusTransaction.requiringNew()
        .run(
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
