package app.statedb.app.auth;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiToken.AccessProfile;
import app.statedb.persistence.identity.ServiceAccountCreator;
import app.statedb.persistence.identity.ServiceAccountRevoker;
import app.statedb.persistence.identity.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the service role's grant set on the Pulumi surface: a CI token can run the deploy loop
 * (read, export, lock, encrypt); stack lifecycle is an explicit credential profile and neither
 * profile grants anything outside its org.
 */
@QuarkusTest
class ServiceAccountAuthorizationTest extends DatabaseTest {

  private static final String ORG = "svc-acct-org";
  private static final String OTHER_ORG = "svc-acct-other-org";
  private static final String PROJECT = "svc-acct-project";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK;

  @Inject ServiceAccountCreator creator;

  @Inject ServiceAccountRevoker revoker;

  private UUID orgId;
  private UUID accountId;
  private String rawToken;

  @BeforeEach
  void seed() {
    orgId = db.seedStack(ORG, PROJECT, STACK).orgId();
    db.seedStack(OTHER_ORG, PROJECT, "secret");

    var minted = requiringNew().call(() -> creator.create(orgId, "ci-deploy", 90, null));
    accountId = minted.accountId();
    rawToken = minted.rawToken();
  }

  @Test
  void deployLoopIsAllowed() {
    asService().get(BASE).then().statusCode(200);
    asService().get(BASE + "/export").then().statusCode(200);

    asService()
        .contentType("application/json")
        .body(Map.of("config", Map.of()))
        .post(BASE + "/update")
        .then()
        .statusCode(200)
        .body("updateID", notNullValue());

    asService()
        .contentType("application/json")
        .body(Map.of("plaintext", "hunter2"))
        .post(BASE + "/encrypt")
        .then()
        .statusCode(200)
        .body("ciphertext", notNullValue());
  }

  @Test
  void stackLifecycleIsDenied() {
    asService().delete(BASE).then().statusCode(403);

    asService()
        .contentType("application/json")
        .body(Map.of("stackName", "new-stack", "tags", Map.of()))
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT)
        .then()
        .statusCode(403);

    asService()
        .contentType("application/json")
        .body(Map.of("k", "v"))
        .patch(BASE + "/tags")
        .then()
        .statusCode(403);

    asService()
        .contentType("application/json")
        .body(Map.of("newName", "renamed", "newProject", PROJECT))
        .post(BASE + "/rename")
        .then()
        .statusCode(403);
  }

  @Test
  void optedInStackLifecycleIsAllowedWithoutRenameOrTagAuthority() {
    String lifecycleToken =
        requiringNew()
            .call(
                () ->
                    creator
                        .create(orgId, "migration-bot", 30, AccessProfile.stack_lifecycle, null)
                        .rawToken());

    asToken(lifecycleToken)
        .contentType("application/json")
        .body(Map.of("stackName", "migrated", "tags", Map.of()))
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT)
        .then()
        .statusCode(200);

    asToken(lifecycleToken)
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/migrated")
        .then()
        .statusCode(200);

    asToken(lifecycleToken)
        .contentType("application/json")
        .body(Map.of("k", "v"))
        .patch(BASE + "/tags")
        .then()
        .statusCode(403);

    asToken(lifecycleToken)
        .contentType("application/json")
        .body(Map.of("newName", "renamed", "newProject", PROJECT))
        .post(BASE + "/rename")
        .then()
        .statusCode(403);

    asToken(lifecycleToken)
        .contentType("application/json")
        .body(Map.of("stackName", "outside", "tags", Map.of()))
        .post("/pulumi/api/stacks/" + OTHER_ORG + "/" + PROJECT)
        .then()
        .statusCode(403);
  }

  @Test
  void lifecycleProfileDoesNotOverrideProtectedStackGuardrail() {
    String lifecycleToken =
        requiringNew()
            .call(
                () ->
                    creator
                        .create(
                            orgId, "migration-protected", 30, AccessProfile.stack_lifecycle, null)
                        .rawToken());

    asToken(lifecycleToken)
        .contentType("application/json")
        .body(Map.of("stackName", "prod", "tags", Map.of()))
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT)
        .then()
        .statusCode(200);
    asToken(lifecycleToken)
        .delete("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/prod")
        .then()
        .statusCode(403);
  }

  @Test
  void crossOrgCollapsesTo404() {
    // single_org credential scope: the other org's stack is invisible, not forbidden.
    asService()
        .get("/pulumi/api/stacks/" + OTHER_ORG + "/" + PROJECT + "/secret")
        .then()
        .statusCode(404);
  }

  @Test
  void revokedAccountStopsAuthenticating() {
    asService().get(BASE).then().statusCode(200);

    boolean revoked = requiringNew().call(() -> revoker.revoke(orgId, accountId, null));
    assertTrue(revoked);

    requiringNew()
        .run(
            () -> {
              User account = User.findById(accountId);
              ApiToken token = ApiToken.find("userId", accountId).firstResult();
              assertNotNull(account);
              assertNotNull(account.deactivatedAt);
              assertNotNull(token.revokedAt);
            });

    asService().get(BASE).then().statusCode(401);
  }

  private io.restassured.specification.RequestSpecification asService() {
    return asToken(rawToken);
  }

  private io.restassured.specification.RequestSpecification asToken(String token) {
    return given().header("Authorization", "token " + token).when();
  }
}
