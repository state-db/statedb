package app.statedb.persistence.stack;

import app.statedb.persistence.NameGrammar;
import app.statedb.persistence.PersistenceExceptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a stack — and the project + workspace + stack_state rows that go with it — in one
 * transaction. Lives in {@code persistence/} per the module-placement rule: write paths against the
 * stack tree are tenant-isolated and must not be reimplemented in any HTTP module.
 */
@ApplicationScoped
public class StackCreator {

  @Inject ProjectUpserter projectUpserter;

  /**
   * @throws StackAlreadyExistsException if {@code stackName} already exists under {@code (orgId,
   *     projectName)} — either because a prior call created it or because a concurrent caller raced
   *     this one to the same unique row.
   */
  @Transactional
  public StackRef create(
      UUID orgId, String orgName, String projectName, String stackName, Map<String, String> tags) {
    NameGrammar.requireValid("project name", projectName);
    NameGrammar.requireValid("stack name", stackName);
    UUID projectId = projectUpserter.upsert(orgId, projectName);

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
      if (PersistenceExceptions.isUniqueViolation(e)) {
        throw new StackAlreadyExistsException(orgName, projectName, stackName);
      }
      throw e;
    }

    StackState state = new StackState();
    state.stackId = stack.id;
    state.journalSeq = 0L;
    state.persist();

    return new StackRef(
        stack.id, workspace.id, orgId, orgName, projectName, stackName, 0, null, stack.visibility);
  }
}
