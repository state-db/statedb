package app.statedb.app.lock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.persistence.lock.LeaseRevalidator;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LeaseRevalidatorTest {

  private static final String ORG_NAME = "revalidator-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject LeaseRevalidator revalidator;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID leaseId;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Revalidator Test";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
    UUID updateId =
        starter
            .start(
                stackId,
                StackUpdate.Kind.PREVIEW,
                null,
                null,
                "rev-key",
                new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    leaseId = lease.leaseId();
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());
  }

  @Test
  void liveLeaseDoesNotThrow() {
    assertDoesNotThrow(() -> revalidator.requireLive(leaseId));
  }

  @Test
  void expiredLeaseThrows() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks SET expires_at = now() - interval '1 second'"
                            + " WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(leaseId));
  }

  @Test
  void missingLeaseThrows() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM stack_locks WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(UUID.randomUUID()));
    assertThrows(LeaseExpiredException.class, () -> revalidator.requireLive(leaseId));
  }
}
