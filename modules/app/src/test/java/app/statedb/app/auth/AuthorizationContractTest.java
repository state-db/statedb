package app.statedb.app.auth;

import static app.statedb.testsupport.TestTransactions.tx;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.bootstrap.BootstrapProvisioner;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.testsupport.TestIdentity;
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
class AuthorizationContractTest {

  private static final String ORG = "authz-org";
  private static final String OTHER_ORG = "authz-other-org";
  private static final String PROJECT = "authz-project";
  private static final String VIEWER_TOKEN = "authz-viewer-token";
  private static final String MEMBER_TOKEN = "authz-member-token";
  private static final String ADMIN_TOKEN = "authz-admin-token";

  @Inject EntityManager em;

  @Inject StackCreator stackCreator;

  private UUID orgId;
  private UUID otherOrgId;

  @BeforeEach
  void seed() {
    tx(
        () -> {
          Organization org = org(ORG);
          orgId = org.id;
          Organization other = org(OTHER_ORG);
          otherOrgId = other.id;

          userWithToken("viewer", OrgMember.Role.viewer, org, VIEWER_TOKEN);
          userWithToken("member", OrgMember.Role.member, org, MEMBER_TOKEN);
          stackCreator.create(org.id, ORG, PROJECT, "member-delete", Map.of());
          stackCreator.create(org.id, ORG, PROJECT, "secrets", Map.of());
          stackCreator.create(org.id, ORG, PROJECT, "prod", Map.of());

          User admin = new User();
          admin.iss = BootstrapProvisioner.BOOTSTRAP_ISS;
          admin.sub = BootstrapProvisioner.BOOTSTRAP_SUB;
          admin.email = "admin@example.com";
          admin.displayName = "Bootstrap Admin";
          admin.persistAndFlush();

          OrgMember adminMember = new OrgMember();
          adminMember.orgId = org.id;
          adminMember.userId = admin.id;
          adminMember.role = OrgMember.Role.viewer;
          adminMember.persistAndFlush();

          ApiToken token = new ApiToken();
          token.tokenHash = TestIdentity.sha256Hex(ADMIN_TOKEN);
          token.userId = admin.id;
          token.orgId = org.id;
          token.kind = ApiToken.Kind.service_account;
          token.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
          token.description = "admin";
          token.persistAndFlush();
        });

    tx(
        () -> {
          stackCreator.create(otherOrgId, OTHER_ORG, PROJECT, "secret", Map.of());
          stackCreator.create(otherOrgId, OTHER_ORG, PROJECT, "delete-me", Map.of());
        });
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
              .setParameter(1, ORG)
              .setParameter(2, OTHER_ORG)
              .executeUpdate();
          em.createNativeQuery("DELETE FROM users WHERE iss IN (?1, ?2)")
              .setParameter(1, "local:test")
              .setParameter(2, BootstrapProvisioner.BOOTSTRAP_ISS)
              .executeUpdate();
        });
  }

  @Test
  void viewerDeniedOnStackCreate() {
    given()
        .header("Authorization", "token " + VIEWER_TOKEN)
        .contentType("application/json")
        .body(Map.of("stackName", "viewer-stack", "tags", Map.of()))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT)
        .then()
        .statusCode(403);
  }

  @Test
  void memberAllowedOnStackCreate() {
    given()
        .header("Authorization", "token " + MEMBER_TOKEN)
        .contentType("application/json")
        .body(Map.of("stackName", "member-stack", "tags", Map.of("pulumi:project", PROJECT)))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT)
        .then()
        .statusCode(200)
        .body("orgName", equalTo(ORG))
        .body("projectName", equalTo(PROJECT))
        .body("stackName", equalTo("member-stack"));
  }

  @Test
  void memberAllowedOnStackDeleteForThisSlice() {
    given()
        .header("Authorization", "token " + MEMBER_TOKEN)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/member-delete")
        .then()
        .statusCode(200);
  }

  @Test
  void explicitDenyBlocksMemberDeletingProtectedStack() {
    // A member can delete ordinary stacks (see memberAllowedOnStackDeleteForThisSlice), but the
    // DenyDeleteProtectedStacks guardrail (an explicit Cedar forbid) blocks deleting a "prod"
    // stack.
    given()
        .header("Authorization", "token " + MEMBER_TOKEN)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/prod")
        .then()
        .statusCode(403);
  }

  @Test
  void explicitDenyOverridesAdministratorAccess() {
    // IAM-style deny-overrides-allow: forbid wins over AdministratorAccess, so even the break-glass
    // admin — who may delete stacks in any org (see bootstrapAdminAllowedAcrossOrgs) — cannot
    // delete a protected "prod" stack.
    given()
        .header("Authorization", "token " + ADMIN_TOKEN)
        .when()
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/prod")
        .then()
        .statusCode(403);
  }

  @Test
  void viewerDeniedOnSecretEncrypt() {
    given()
        .header("Authorization", "token " + VIEWER_TOKEN)
        .contentType("application/json")
        .body(Map.of("plaintext", "secret"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/secrets/encrypt")
        .then()
        .statusCode(403);
  }

  @Test
  void viewerAllowedOnSecretDecryptForReadFlows() {
    String ciphertext =
        given()
            .header("Authorization", "token " + MEMBER_TOKEN)
            .contentType("application/json")
            .body(Map.of("plaintext", "preview-secret"))
            .when()
            .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/secrets/encrypt")
            .then()
            .statusCode(200)
            .extract()
            .path("ciphertext");

    given()
        .header("Authorization", "token " + VIEWER_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertext", ciphertext))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/secrets/decrypt")
        .then()
        .statusCode(200)
        .body("plaintext", equalTo("preview-secret"));
  }

  @Test
  void viewerAllowedOnBatchSecretDecryptForPreviewFlows() {
    String ciphertext =
        given()
            .header("Authorization", "token " + MEMBER_TOKEN)
            .contentType("application/json")
            .body(Map.of("plaintext", "batch-secret"))
            .when()
            .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/secrets/encrypt")
            .then()
            .statusCode(200)
            .extract()
            .path("ciphertext");

    given()
        .header("Authorization", "token " + VIEWER_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertexts", List.of(ciphertext)))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/secrets/batch-decrypt")
        .then()
        .statusCode(200)
        .body("plaintexts.\"" + ciphertext + "\"", equalTo("batch-secret"));
  }

  @Test
  void crossOrgReadReturns404BeforePdpDeny() {
    given()
        .header("Authorization", "token " + VIEWER_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + OTHER_ORG + "/" + PROJECT + "/secret")
        .then()
        .statusCode(404);
  }

  @Test
  void bootstrapAdminAllowedAcrossOrgs() {
    given()
        .header("Authorization", "token " + ADMIN_TOKEN)
        .when()
        .get("/pulumi/api/stacks/" + OTHER_ORG + "/" + PROJECT + "/secret")
        .then()
        .statusCode(200)
        .body("orgName", equalTo(OTHER_ORG));

    given()
        .header("Authorization", "token " + ADMIN_TOKEN)
        .when()
        .delete("/pulumi/api/stacks/" + OTHER_ORG + "/" + PROJECT + "/delete-me")
        .then()
        .statusCode(200);
  }

  private static Organization org(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    org.persistAndFlush();
    return org;
  }

  private static void userWithToken(
      String sub, OrgMember.Role role, Organization org, String rawToken) {
    User user = new User();
    user.iss = "local:test";
    user.sub = "authz-" + sub;
    user.email = sub + "@example.com";
    user.displayName = sub;
    user.persistAndFlush();

    OrgMember member = new OrgMember();
    member.orgId = org.id;
    member.userId = user.id;
    member.role = role;
    member.persistAndFlush();

    ApiToken token = new ApiToken();
    token.tokenHash = TestIdentity.sha256Hex(rawToken);
    token.userId = user.id;
    token.orgId = org.id;
    token.kind = ApiToken.Kind.service_account;
    token.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
    token.description = sub;
    token.persistAndFlush();
  }
}
