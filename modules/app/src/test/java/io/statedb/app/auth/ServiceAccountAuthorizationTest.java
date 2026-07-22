package io.statedb.app.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.ServiceAccountCreator;
import io.statedb.persistence.identity.ServiceAccountRevoker;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the service role's grant set on the Pulumi surface: a CI token can run the deploy loop
 * (read, export, lock, encrypt) but holds no stack-lifecycle powers (create, delete, tags) and
 * nothing outside its org.
 */
@QuarkusTest
class ServiceAccountAuthorizationTest {

  private static final String ORG = "svc-acct-org";
  private static final String OTHER_ORG = "svc-acct-other-org";
  private static final String PROJECT = "svc-acct-project";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK;

  @Inject EntityManager em;

  @Inject ServiceAccountCreator creator;

  @Inject ServiceAccountRevoker revoker;

  @Inject io.statedb.persistence.stack.StackCreator stackCreator;

  private UUID orgId;
  private UUID accountId;
  private String rawToken;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = "Service Account Test";
              org.persistAndFlush();
              orgId = org.id;

              Organization other = new Organization();
              other.name = OTHER_ORG;
              other.displayName = "Other Org";
              other.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
              stackCreator.create(other.id, OTHER_ORG, PROJECT, "secret", Map.of());
            });

    var minted =
        QuarkusTransaction.requiringNew().call(() -> creator.create(orgId, "ci-deploy", 90, null));
    accountId = minted.accountId();
    rawToken = minted.rawToken();
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM users WHERE iss = 'local:service'").executeUpdate();
              em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
                  .setParameter(1, ORG)
                  .setParameter(2, OTHER_ORG)
                  .executeUpdate();
            });
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

    boolean revoked =
        QuarkusTransaction.requiringNew().call(() -> revoker.revoke(orgId, accountId, null));
    org.junit.jupiter.api.Assertions.assertTrue(revoked);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              User account = User.findById(accountId);
              ApiToken token = ApiToken.find("userId", accountId).firstResult();
              org.junit.jupiter.api.Assertions.assertNotNull(account);
              org.junit.jupiter.api.Assertions.assertNotNull(account.deactivatedAt);
              org.junit.jupiter.api.Assertions.assertNotNull(token.revokedAt);
            });

    asService().get(BASE).then().statusCode(401);
  }

  private io.restassured.specification.RequestSpecification asService() {
    return given().header("Authorization", "token " + rawToken).when();
  }
}
