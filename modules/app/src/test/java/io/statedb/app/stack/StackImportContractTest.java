package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.StackCreator;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = ORG;
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "import";
              user.email = "import@example.com";
              user.displayName = "Import Tester";
              user.persistAndFlush();

              OrgMember member = new OrgMember();
              member.orgId = org.id;
              member.userId = user.id;
              member.role = OrgMember.Role.owner;
              member.persistAndFlush();

              ApiToken token = new ApiToken();
              token.tokenHash = sha256Hex(RAW_TOKEN);
              token.userId = user.id;
              token.orgId = org.id;
              token.kind = ApiToken.Kind.service_account;
              token.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
              token.description = "import";
              token.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "import")
                  .executeUpdate();
            });
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
    QuarkusTransaction.requiringNew()
        .run(
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

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
