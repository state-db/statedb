package io.statedb.app.console;

import static io.restassured.RestAssured.given;
import static io.statedb.app.console.LocalGlassTestSupport.COOKIE;
import static io.statedb.app.console.LocalGlassTestSupport.PASSWORD;
import static io.statedb.app.console.LocalGlassTestSupport.login;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.statedb.persistence.bootstrap.BootstrapProvisioner;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.ServiceAccountCreator;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.stack.CompactionJob;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** HTTP contract for the site-admin locks and compaction queue inventory. */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
class AdminOperationsResourceTest {
  private static final String ORG = "s14-operations";
  private static final String PROJECT = "infra";
  private static final String STACK = "production";

  @Inject StackCreator stackCreator;

  private UUID serviceHolderId;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization.delete("name", ORG);
              if (serviceHolderId != null) {
                User.deleteById(serviceHolderId);
              }
            });
  }

  @Test
  void listsLiveLocksAndFailedJobsAndGuardedlyCancelsTheDisplayedUpdate() {
    String cookie = login(PASSWORD).statusCode(200).extract().cookie(COOKIE);
    Seed seed = seed();

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/locks")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].lockId", is(seed.lockId().toString()))
        .body("[0].org", is(ORG))
        .body("[0].project", is(PROJECT))
        .body("[0].stack", is(STACK))
        .body("[0].holder.kind", is("service"))
        .body("[0].holder.displayName", is("deploy-bot"))
        .body("[0].heartbeatAt", notNullValue())
        .body("[0].update.id", is(seed.updateId().toString()))
        .body("[0].update.version", is(1))
        .body("[0].update.preview", is(false));

    given()
        .cookie(COOKIE, cookie)
        .when()
        .get("/api/admin/compaction-jobs")
        .then()
        .statusCode(200)
        .body("$", hasSize(2))
        .body("[0].status", is("pending"))
        .body("[0].attempts", is(1))
        .body("[0].errorMessage", is("database was temporarily unavailable"))
        .body("[1].status", is("failed"))
        .body("[1].attempts", is(3))
        .body("[1].errorMessage", is("snapshot integrity check failed"));

    String path = "/api/admin/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/lock";
    given()
        .cookie(COOKIE, cookie)
        .queryParam("lockId", UUID.randomUUID())
        .when()
        .delete(path)
        .then()
        .statusCode(409)
        .body("error", is("The lock changed or was already released."));

    given()
        .cookie(COOKIE, cookie)
        .queryParam("lockId", seed.lockId())
        .when()
        .delete(path)
        .then()
        .statusCode(204);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(0L, StackLock.count("id", seed.lockId()));
              StackUpdate update = StackUpdate.findById(seed.updateId());
              assertEquals(StackUpdate.Status.CANCELLED, update.status);
              assertNotNull(update.endedAt);
              assertNull(Stack.<Stack>findById(seed.stackId()).activeUpdateId);
            });
  }

  private Seed seed() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              User bootstrap =
                  User.<User>find(
                          "iss = ?1 and sub = ?2",
                          BootstrapProvisioner.BOOTSTRAP_ISS,
                          BootstrapProvisioner.BOOTSTRAP_SUB)
                      .singleResult();

              Organization org = new Organization();
              org.name = ORG;
              org.displayName = "S14 operations";
              org.persist();
              StackRef stack = stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());

              User service = new User();
              service.iss = ServiceAccountCreator.SERVICE_ISS;
              service.sub = UUID.randomUUID().toString();
              service.displayName = "deploy-bot";
              service.persist();
              serviceHolderId = service.id;

              Instant now = Instant.now();
              StackUpdate update = new StackUpdate();
              update.stackId = stack.stackId();
              update.version = 1;
              update.dryRun = false;
              update.kind = StackUpdate.Kind.UPDATE;
              update.status = StackUpdate.Status.IN_PROGRESS;
              update.startedByUserId = service.id;
              update.startedAt = now;
              update.persist();

              Stack stackEntity = Stack.findById(stack.stackId());
              stackEntity.activeUpdateId = update.id;

              StackLock live = new StackLock();
              live.stackId = stack.stackId();
              live.updateId = update.id;
              live.lockedBy = "user:" + service.id;
              live.lockTokenHash = UUID.randomUUID().toString().getBytes();
              live.lockedAt = now.minusSeconds(60);
              live.heartbeatAt = now.minusSeconds(5);
              live.expiresAt = now.plusSeconds(300);
              live.maxExpiresAt = now.plusSeconds(600);
              live.persist();

              CompactionJob failed = new CompactionJob();
              failed.stackId = stack.stackId();
              failed.status = CompactionJob.Status.FAILED;
              failed.attempts = 3;
              failed.errorMessage = "snapshot integrity check failed";
              failed.startedAt = now.minusSeconds(20);
              failed.completedAt = now.minusSeconds(10);
              failed.persist();

              CompactionJob retrying = new CompactionJob();
              retrying.stackId = stack.stackId();
              retrying.status = CompactionJob.Status.PENDING;
              retrying.attempts = 1;
              retrying.errorMessage = "database was temporarily unavailable";
              retrying.persist();

              // An expired row remains physically present until a later acquisition steals it,
              // but it is not an active lock and must not appear in the inventory.
              StackRef expiredStack =
                  stackCreator.create(org.id, ORG, PROJECT, "expired", Map.of());
              StackUpdate expiredUpdate = new StackUpdate();
              expiredUpdate.stackId = expiredStack.stackId();
              expiredUpdate.version = 1;
              expiredUpdate.dryRun = false;
              expiredUpdate.kind = StackUpdate.Kind.UPDATE;
              expiredUpdate.status = StackUpdate.Status.IN_PROGRESS;
              expiredUpdate.startedByUserId = bootstrap.id;
              expiredUpdate.startedAt = now.minusSeconds(600);
              expiredUpdate.persist();
              StackLock expired = new StackLock();
              expired.stackId = expiredStack.stackId();
              expired.updateId = expiredUpdate.id;
              expired.lockedBy = "user:" + bootstrap.id;
              expired.lockTokenHash = UUID.randomUUID().toString().getBytes();
              expired.lockedAt = now.minusSeconds(600);
              expired.heartbeatAt = now.minusSeconds(400);
              expired.expiresAt = now.minusSeconds(300);
              expired.maxExpiresAt = now.minusSeconds(200);
              expired.persist();

              return new Seed(stack.stackId(), update.id, live.id);
            });
  }

  private record Seed(UUID stackId, UUID updateId, UUID lockId) {}
}
