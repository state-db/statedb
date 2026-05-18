package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackAlreadyExistsException;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
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
}
