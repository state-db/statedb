package app.statedb.persistence.stack;

import app.statedb.persistence.lock.StackLockedException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.UUID;

/** Deletes a stack by deleting its workspace row and relying on DB-side cascade for child rows. */
@ApplicationScoped
public class StackDeleter {

  private static final String ROOT_STACK_TYPE = "pulumi:pulumi:Stack";

  @Inject StackAssembler assembler;
  @Inject EntityManager em;

  @Transactional
  public void delete(StackRef ref, boolean force) {
    // Stack deletion cascades through snapshots and every other state table. Serialize it at the
    // same row used by lease acquisition and compaction, then inspect active_update_id while that
    // lock is held so a new update cannot start between the guard and the workspace delete.
    StackState state =
        StackState.<StackState>find("stackId", ref.stackId())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    if (state == null) {
      return;
    }

    // Keep Stack out of the persistence context: hydrating its read-only Workspace association
    // before the bulk parent delete makes Hibernate reject the DB-side cascade at flush time.
    UUID activeUpdateId =
        em.createQuery("select s.activeUpdateId from Stack s where s.id = :stackId", UUID.class)
            .setParameter("stackId", ref.stackId())
            .getSingleResult();
    // Pulumi's forced removal is the explicit escape hatch for abandoning managed resources and
    // an active operation. The state-row lock still serializes its cascading delete with lease
    // acquisition and compaction.
    if (!force && activeUpdateId != null) {
      throw new StackLockedException(ref.stackId());
    }

    if (!force && hasResources(ref)) {
      throw new StackNotEmptyException(ref.orgName(), ref.projectName(), ref.stackName());
    }

    Workspace.deleteById(ref.workspaceId());
  }

  private boolean hasResources(StackRef ref) {
    JsonNode resources = assembler.assemble(ref.stackId()).deployment().required("resources");
    for (JsonNode resource : resources) {
      if (!ROOT_STACK_TYPE.equals(resource.path("type").asText())) {
        return true;
      }
    }
    return false;
  }
}
