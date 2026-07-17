package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * With no {@code state-db.local-glass.password-hash} configured (the default), the break-glass
 * login rejects everything and the providers endpoint does not advertise it.
 */
@QuarkusTest
class LocalGlassDisabledTest {

  @Test
  void loginRejectedWhenNotConfigured() {
    given()
        .contentType("application/x-www-form-urlencoded")
        .formParam("username", "admin")
        .formParam("password", "anything")
        .redirects()
        .follow(false)
        .when()
        .post("/api/auth/local")
        .then()
        .statusCode(401);
  }

  @Test
  void providersDoNotAdvertiseBreakGlass() {
    given().when().get("/api/auth/providers").then().statusCode(200).body("breakGlass", is(false));
  }
}
