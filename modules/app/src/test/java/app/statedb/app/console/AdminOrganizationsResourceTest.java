package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.StackCreator;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** HTTP contract and destructive guards for site-admin organization lifecycle operations. */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class AdminOrganizationsResourceTest {
  private static final List<String> TEST_ORGS = List.of("empty-admin-org", "populated-admin-org");

  @Inject StackCreator stackCreator;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> Organization.delete("name in ?1", TEST_ORGS));
  }

  @Test
  void createsAndDeletesOnlyEmptyOrganizations() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    String emptyId = createOrg(cookie, TEST_ORGS.get(0));
    String populatedId = createOrg(cookie, TEST_ORGS.get(1));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization populated =
                  Organization.<Organization>find("name", TEST_ORGS.get(1)).firstResult();
              stackCreator.create(populated.id, populated.name, "project", "stack", Map.of());
            });

    given()
        .cookie(COOKIE, cookie)
        .when()
        .delete("/api/admin/orgs/" + populatedId)
        .then()
        .statusCode(409);
    given()
        .cookie(COOKIE, cookie)
        .when()
        .delete("/api/admin/orgs/" + emptyId)
        .then()
        .statusCode(200);
  }

  @Test
  void rejectsReservedAndInvalidNames() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    for (String name : List.of("console", "contains spaces")) {
      given()
          .cookie(COOKIE, cookie)
          .contentType("application/json")
          .body(Map.of("name", name, "displayName", "Rejected"))
          .when()
          .post("/api/admin/orgs")
          .then()
          .statusCode(400);
    }
  }

  private static String createOrg(String cookie, String name) {
    return given()
        .cookie(COOKIE, cookie)
        .contentType("application/json")
        .body(Map.of("name", name, "displayName", name))
        .when()
        .post("/api/admin/orgs")
        .then()
        .statusCode(200)
        .body("members", is(0))
        .body("stacks", is(0))
        .extract()
        .path("id");
  }
}
