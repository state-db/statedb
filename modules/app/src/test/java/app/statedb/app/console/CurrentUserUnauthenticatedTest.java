package app.statedb.app.console;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Verifies the public and authenticated edges of the console HTTP permission boundary. */
@QuarkusTest
class CurrentUserUnauthenticatedTest {

  @Test
  void anonymousUserReturns401() {
    given().redirects().follow(false).when().get("/api/user").then().statusCode(401);
  }
}
