package app.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackAlreadyExistsException;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
  @Transactional
  void seed() {
    Organization org = new Organization();
    org.name = ORG_NAME;
    org.displayName = "Stack Creator Test";
    org.persistAndFlush();
    orgId = org.id;
  }

  @AfterEach
  @Transactional
  void cleanup() {
    em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
        .setParameter(1, ORG_NAME)
        .executeUpdate();
  }

  @TestTransaction
  @Test
  void createsStackAndChildRows() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, "proj", "dev", Map.of("k", "v"));
    assertEquals(orgId, ref.orgId());
    assertEquals("dev", ref.stackName());

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

  @TestTransaction
  @Test
  void projectUpsertIsIdempotentAcrossCalls() {
    stackCreator.create(orgId, ORG_NAME, "shared-proj", "alpha", Map.of());
    stackCreator.create(orgId, ORG_NAME, "shared-proj", "beta", Map.of());

    Number projects =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM projects WHERE org_id = ?1 AND name = ?2")
                .setParameter(1, orgId)
                .setParameter(2, "shared-proj")
                .getSingleResult();
    assertEquals(1L, projects.longValue());
  }
}
