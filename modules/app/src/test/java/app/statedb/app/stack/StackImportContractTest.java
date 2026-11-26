package app.statedb.app.stack;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.contract.PulumiContractIdentity;
import app.statedb.persistence.stack.StackCreator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackImportContractTest {

  private static final String ORG = "import-org";
  private static final String PROJECT = "proj";
  private static final String STACK = "dev";
  private static final String RAW_TOKEN = "import-token";
  private static final String ROOT_URN = "urn:pulumi:dev::proj::pulumi:pulumi:Stack::proj-dev";

  @Inject StackCreator stackCreator;
  @Inject EntityManager em;

  private static final PulumiContractIdentity IDENTITY =
      new PulumiContractIdentity(
          ORG, ORG, "import", "import@example.com", "Import Tester", RAW_TOKEN, "import");

  @BeforeEach
  void seed() {
    IDENTITY.seedStack(stackCreator, PROJECT, STACK);
  }

  @AfterEach
  void cleanup() {
    IDENTITY.cleanup();
  }

  @Test
  void importStoresDeploymentAndCanBeExported() {
    String updateId =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .contentType("application/json")
            .body(importBody())
            .when()
            .post(importPath())
            .then()
            .statusCode(200)
            .body("updateId", notNullValue())
            .extract()
            .path("updateId");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(updatePath(updateId))
        .then()
        .statusCode(200)
        .body("status", equalTo("succeeded"))
        .body("events", equalTo(List.of()));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(exportPath())
        .then()
        .statusCode(200)
        .body("version", equalTo(3))
        .body("deployment.resources[0].urn", equalTo(ROOT_URN))
        .body("deployment.resources[0].outputs.message", equalTo("imported"));

    UUID updateUuid = UUID.fromString(updateId);
    assertImportRecordedAtInitialBoundary(updateUuid);
  }

  @Test
  void activeUpdateBlocksImport() {
    String updateId = startUpdate();
    acquireLock(updateId);

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(importBody())
        .when()
        .post(importPath())
        .then()
        .statusCode(409);
  }

  @Test
  void unsupportedDeploymentVersionReturns400() {
    Map<String, Object> body =
        Map.of("version", 4, "features", List.of("x"), "deployment", deployment());

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post(importPath())
        .then()
        .statusCode(400);
  }

  private String startUpdate() {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", PROJECT, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update")
        .then()
        .statusCode(200)
        .extract()
        .path("updateID");
  }

  private void acquireLock(String updateId) {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(200);
  }

  private static Map<String, Object> importBody() {
    return Map.of("version", 3, "deployment", deployment());
  }

  private static Map<String, Object> deployment() {
    return Map.of(
        "manifest",
        Map.of("time", "2026-01-01T00:00:00Z", "magic", "state-db", "version", "v3.234.0"),
        "resources",
        List.of(
            Map.of(
                "urn",
                ROOT_URN,
                "custom",
                false,
                "type",
                "pulumi:pulumi:Stack",
                "outputs",
                Map.of("message", "imported"))));
  }

  private static String importPath() {
    return "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/import";
  }

  private static String exportPath() {
    return "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/export";
  }

  private static String updatePath(String updateId) {
    return "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId;
  }

  private void assertImportRecordedAtInitialBoundary(UUID updateUuid) {
    tx(
        () -> {
          Object[] row =
              (Object[])
                  em.createNativeQuery(
                          "SELECT kind, status, start_journal_seq, end_journal_seq "
                              + "FROM stack_updates WHERE id = ?1")
                      .setParameter(1, updateUuid)
                      .getSingleResult();
          assertEquals("import", row[0]);
          assertEquals("succeeded", row[1]);
          assertEquals(0L, ((Number) row[2]).longValue());
          assertEquals(0L, ((Number) row[3]).longValue());
        });
  }
}
