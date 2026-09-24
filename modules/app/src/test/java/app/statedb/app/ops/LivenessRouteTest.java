package app.statedb.app.ops;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LivenessRouteTest {

  @Test
  void respondsWithoutASession() {
    // The body rules out SpaRouteFilter, which answers unknown GETs with index.html and a 200.
    given().when().get("/health/live").then().statusCode(200).body(equalTo("ok"));
  }
}
