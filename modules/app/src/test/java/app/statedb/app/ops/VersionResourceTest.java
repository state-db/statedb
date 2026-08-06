package app.statedb.app.ops;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class VersionResourceTest {

  /**
   * Unfiltered placeholders would ship silently; under {@code mvn test} values are pom defaults.
   */
  @Test
  void buildInfoIsFilteredAtPackageTime() {
    BuildInfo info = BuildInfo.fromClasspath();

    for (String value : new String[] {info.commit(), info.builtAt(), info.build()}) {
      assertNotNull(value);
      assertFalse(
          value.contains("${"), "unfiltered placeholder in build-info.properties: " + value);
    }
  }

  @Test
  void versionRequiresAuthentication() {
    assertEquals(401, given().when().get("/api/version").getStatusCode());
  }
}
