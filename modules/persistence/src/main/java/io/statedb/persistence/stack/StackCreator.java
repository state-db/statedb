package io.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Creates a stack — and the project + workspace + stack_state rows that go with it — in one
 * transaction. Lives in {@code persistence/} per the module-placement rule: write paths against the
 * stack tree are tenant-isolated and must not be reimplemented in any HTTP module.
 */
@ApplicationScoped
public class StackCreator {

  @Inject EntityManager em;

  /**
   * @throws StackAlreadyExistsException if {@code stackName} already exists under {@code (orgId,
   *     projectName)} — either because a prior call created it or because a concurrent caller raced
   *     this one to the same unique row.
   */
  @Transactional
  public StackRef create(
      UUID orgId, String orgName, String projectName, String stackName, Map<String, String> tags) {
    UUID projectId = upsertProject(orgId, projectName);

    Workspace workspace = new Workspace();
    workspace.orgId = orgId;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persist();

    Stack stack = new Stack();
    stack.orgId = orgId;
    stack.projectId = projectId;
    stack.name = stackName;
    stack.workspaceId = workspace.id;
    stack.version = 0;
    stack.tags = tags;
    try {
      stack.persistAndFlush();
    } catch (PersistenceException e) {
      if (isUniqueViolation(e)) {
        throw new StackAlreadyExistsException(orgName, projectName, stackName);
      }
      throw e;
    }

    StackState state = new StackState();
    state.stackId = stack.id;
    state.journalSeq = 0L;
    state.persist();

    return new StackRef(stack.id, workspace.id, orgId, orgName, projectName, stackName, 0, null);
  }

  // Concurrency-safe project upsert: ON CONFLICT DO NOTHING then SELECT. Two concurrent first-
  // stack inits in a new project must not produce two project rows.
  private UUID upsertProject(UUID orgId, String name) {
    em.createNativeQuery(
            "INSERT INTO projects (id, org_id, name) VALUES (?1, ?2, ?3) "
                + "ON CONFLICT (org_id, name) DO NOTHING")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, orgId)
        .setParameter(3, name)
        .executeUpdate();
    return Project.find("orgId = ?1 AND name = ?2", orgId, name).<Project>firstResult().id;
  }

  private static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ConstraintViolationException) {
        return true;
      }
    }
    return false;
  }
}
