package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackExportContractTest {

  private static final String ORG = "export-org";
  private static final String OTHER_ORG = "export-other-org";
  private static final String PROJECT = "proj";
  private static final String STACK = "dev";
  private static final String RAW_TOKEN = "export-token";

  @Inject StackCreator stackCreator;
  @Inject EntityManager em;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = org(ORG);
              Organization other = org(OTHER_ORG);

              User user = new User();
              user.iss = "local:test";
              user.sub = "export";
              user.email = "export@example.com";
              user.displayName = "Export Tester";
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
              token.description = "export";
              token.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
              stackCreator.create(other.id, OTHER_ORG, PROJECT, STACK, Map.of());
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
                  .setParameter(1, ORG)
                  .setParameter(2, OTHER_ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "export")
                  .executeUpdate();
            });
  }

  @Test
  void emptyStackExportsUntypedDeploymentShape() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK))
        .then()
        .statusCode(200)
        .body("version", equalTo(3))
        .body("features", nullValue())
        .body("deployment.resources", hasSize(0))
        .body("deployment.manifest.version", equalTo("v3.242.0"));
  }

  @Test
  void unknownStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, "missing"))
        .then()
        .statusCode(404);
  }

  @Test
  void crossOrgReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(OTHER_ORG, PROJECT, STACK))
        .then()
        .statusCode(404);
  }

  @Test
  void noAuthReturns401() {
    given().when().get(path(ORG, PROJECT, STACK)).then().statusCode(401);
  }

  @Test
  void updateTokenDoesNotAuthorizeExport() {
    given()
        .header("Authorization", "update-token whatever")
        .when()
        .get(path(ORG, PROJECT, STACK))
        .then()
        .statusCode(401);
  }

  private Organization org(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    org.persistAndFlush();
    return org;
  }

  private static String path(String org, String project, String stack) {
    return "/pulumi/api/stacks/" + org + "/" + project + "/" + stack + "/export";
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
