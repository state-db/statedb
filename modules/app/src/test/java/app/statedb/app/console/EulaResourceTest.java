package app.statedb.app.console;

import static app.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static app.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static app.statedb.app.console.LocalGlassTestSupport.login;
import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;

import app.statedb.persistence.eula.EulaAcknowledgment;
import app.statedb.persistence.eula.EulaDocument;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP contract for the site-admin EULA view and acceptance. Shares {@code LocalGlassTestSupport}'s
 * profile so this class does not add a Quarkus restart to the module's suite.
 */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class EulaResourceTest {

  @Inject EulaDocument document;
  @Inject EulaAcknowledgment acknowledgment;

  @AfterEach
  void leaveTheDeploymentAccepted() {
    acknowledgment.acceptFromConfiguration();
  }

  @Test
  void servesTheBundledAgreementAndItsDigest() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/eula")
        .then()
        .statusCode(200)
        .body("version", is(document.version()))
        .body("documentSha256", matchesRegex("^[0-9a-f]{64}$"))
        .body("text", containsString("End User License Agreement"))
        .body("text", containsString("STATEDB INC."))
        .body("accepted", is(true))
        .body("acceptedAt", notNullValue());
  }

  @Test
  void rejectsAcceptanceOfAVersionThisImageDoesNotShip() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    given()
        .cookie(COOKIE, cookie)
        .contentType(JSON)
        .body("{\"version\":\"0.9\"}")
        .when()
        .post("/api/admin/eula/acceptance")
        .then()
        .statusCode(400);
  }

  @Test
  void acceptanceRequiresASession() {
    given().when().get("/api/admin/eula").then().statusCode(401);

    given()
        .contentType(JSON)
        .body("{\"version\":\"" + document.version() + "\"}")
        .when()
        .post("/api/admin/eula/acceptance")
        .then()
        .statusCode(401);
  }

  @Test
  void recordsTheAcceptingAdministratorAndOpensStackCreation() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);

    given()
        .cookie(COOKIE, cookie)
        .contentType(JSON)
        .body("{\"version\":\"" + document.version() + "\"}")
        .when()
        .post("/api/admin/eula/acceptance")
        .then()
        .statusCode(200)
        .body("accepted", is(true))
        .body("version", is(document.version()))
        .body("documentSha256", is(document.sha256()));
  }
}
