package app.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.entitlement.EntitlementDeniedException;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackAlreadyExistsException;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.Workspace;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Persistence-level tests for {@link StackCreator}. Validates the constraint-violation translation
 * (P2 — duplicate-create race must surface as a domain exception, not a 500) and the project upsert
 * idempotency.
 */
@QuarkusTest
class StackCreatorTest {

  private static final String ORG_NAME = "stack-creator-org";

  @Inject StackCreator stackCreator;

  @Inject EntityManager em;

  private UUID orgId;

  @BeforeEach
  void seed() {
    orgId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Stack Creator Test";
                  org.persistAndFlush();
                  return org.id;
                });
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
  void createsStackAndChildRows() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, "proj", "dev", Map.of("k", "v"));
    assertEquals(orgId, ref.orgId());
    assertEquals("dev", ref.stackName());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Stack stack = Stack.findById(ref.stackId());
              assertEquals("dev", stack.name);
              assertEquals(0, stack.version);
              UUID projectId =
                  (UUID)
                      em.createNativeQuery("SELECT project_id FROM stacks WHERE id = ?1")
                          .setParameter(1, ref.stackId())
                          .getSingleResult();
              assertEquals(7, projectId.version());
              Number states =
                  (Number)
                      em.createNativeQuery("SELECT count(*) FROM stack_state WHERE stack_id = ?1")
                          .setParameter(1, ref.stackId())
                          .getSingleResult();
              assertEquals(1L, states.longValue());
            });
  }

  @Test
  void duplicateRaisesStackAlreadyExists() {
    stackCreator.create(orgId, ORG_NAME, "proj", "dup", Map.of());
    StackAlreadyExistsException ex =
        assertThrows(
            StackAlreadyExistsException.class,
            () -> stackCreator.create(orgId, ORG_NAME, "proj", "dup", Map.of()));
    assertTrue(ex.qualifiedName().contains("dup"));
  }

  @Test
  void projectUpsertIsIdempotentAcrossCalls() {
    stackCreator.create(orgId, ORG_NAME, "shared-proj", "alpha", Map.of());
    stackCreator.create(orgId, ORG_NAME, "shared-proj", "beta", Map.of());

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Number projects =
                  (Number)
                      em.createNativeQuery(
                              "SELECT count(*) FROM projects WHERE org_id = ?1 AND name = ?2")
                          .setParameter(1, orgId)
                          .setParameter(2, "shared-proj")
                          .getSingleResult();
              assertEquals(1L, projects.longValue());
            });
  }

  @Test
  void communityAllowsTenThenDeletingRestoresCapacity() {
    List<StackRef> refs = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      refs.add(stackCreator.create(orgId, ORG_NAME, "limit-proj", "stack-" + i, Map.of()));
    }
    assertThrows(
        EntitlementDeniedException.class,
        () -> stackCreator.create(orgId, ORG_NAME, "limit-proj", "stack-11", Map.of()));

    QuarkusTransaction.requiringNew().run(() -> Workspace.deleteById(refs.get(0).workspaceId()));
    stackCreator.create(orgId, ORG_NAME, "limit-proj", "replacement", Map.of());
  }

  @Test
  void concurrentCreationAtNineCannotProduceElevenStacks() throws Exception {
    for (int i = 1; i <= 9; i++) {
      stackCreator.create(orgId, ORG_NAME, "race-proj", "stack-" + i, Map.of());
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          pool.submit(
              () -> stackCreator.create(orgId, ORG_NAME, "race-proj", "stack-10a", Map.of()));
      Future<?> second =
          pool.submit(
              () -> stackCreator.create(orgId, ORG_NAME, "race-proj", "stack-10b", Map.of()));
      int succeeded = 0;
      for (Future<?> result : List.of(first, second)) {
        try {
          result.get(10, TimeUnit.SECONDS);
          succeeded++;
        } catch (java.util.concurrent.ExecutionException e) {
          assertTrue(e.getCause() instanceof EntitlementDeniedException);
        }
      }
      assertEquals(1, succeeded);
      assertEquals(10L, QuarkusTransaction.requiringNew().call(() -> Stack.count()).longValue());
    } finally {
      pool.shutdownNow();
    }
  }
}
