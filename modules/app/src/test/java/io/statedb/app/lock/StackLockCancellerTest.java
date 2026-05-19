package io.statedb.app.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.lock.StackLockAcquirer;
import io.statedb.persistence.lock.StackLockCanceller;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackLockCancellerTest {

  private static final String ORG_NAME = "canceller-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackLockCanceller canceller;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Canceller Test";
                  org.persistAndFlush();
                  StackRef ref =
                      stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
    updateId =
        starter.start(
                stackId,
                StackUpdate.Kind.PREVIEW,
                "cancel-key",
                new StartUpdateRequest(
                    Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
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
  void cancelRemovesLockAndTransitionsUpdate() {
    canceller.cancel(stackId, updateId);

    Number locks =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM stack_locks WHERE stack_id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertEquals(0L, locks.longValue());

    String status =
        (String)
            em.createNativeQuery("SELECT status FROM stack_updates WHERE id = ?1")
                .setParameter(1, updateId)
                .getSingleResult();
    assertEquals("cancelled", status);

    UUID active =
        (UUID)
            em.createNativeQuery("SELECT active_update_id FROM stacks WHERE id = ?1")
                .setParameter(1, stackId)
                .getSingleResult();
    assertNull(active);
  }
}
