package app.statedb.app.stack;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackAlreadyExistsException;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Persistence-level tests for {@link StackCreator}. Validates the constraint-violation translation
 * (P2 — duplicate-create race must surface as a domain exception, not a 500) and the project upsert
 * idempotency.
 */
@QuarkusTest
class StackCreatorTest extends DatabaseTest {

  private static final String ORG_NAME = "stack-creator-org";

  @Inject StackCreator stackCreator;

  private UUID orgId;

  @BeforeEach
  void seed() {
    orgId = db.seedOrg(ORG_NAME, "Stack Creator Test");
  }

  @Test
  void createsStackAndChildRows() {
    StackRef ref = stackCreator.create(orgId, ORG_NAME, "proj", "dev", Map.of("k", "v"));
    assertEquals(orgId, ref.orgId());
    assertEquals("dev", ref.stackName());

    requiringNew()
        .run(
            () -> {
              Stack stack = Stack.findById(ref.stackId());
              assertEquals("dev", stack.name);
              assertEquals(0, stack.version);
              assertEquals(7, stack.projectId.version());
            });
    assertEquals(1L, StackState.count("stackId", ref.stackId()));
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

    assertEquals(1L, Project.count("orgId = ?1 and name = ?2", orgId, "shared-proj"));
  }
}
