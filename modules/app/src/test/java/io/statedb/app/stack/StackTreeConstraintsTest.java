package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Schema-contract tests for the stack-tree tables. */
@QuarkusTest
class StackTreeConstraintsTest {

  @Test
  @TestTransaction
  void projectNameUniquePerOrg() {
    Organization orgA = newOrg("acme-proj-uniq-a");
    orgA.persistAndFlush();
    Organization orgB = newOrg("acme-proj-uniq-b");
    orgB.persistAndFlush();

    persistProject(orgA.id, "shared");
    persistProject(orgB.id, "shared");

    Project dup = new Project();
    dup.orgId = orgA.id;
    dup.name = "shared";
    assertThrows(Exception.class, dup::persistAndFlush);
  }

  @Test
  @TestTransaction
  void stackNameUniquePerProject() {
    Organization org = newOrg("acme-stack-uniq");
    org.persistAndFlush();
    Project projectA = persistProject(org.id, "proj-a");
    Project projectB = persistProject(org.id, "proj-b");

    persistStack(org.id, projectA.id, persistWorkspace(org.id).id, "dev");
    persistStack(org.id, projectB.id, persistWorkspace(org.id).id, "dev");

    Stack dup = new Stack();
    dup.orgId = org.id;
    dup.projectId = projectA.id;
    dup.workspaceId = persistWorkspace(org.id).id;
    dup.name = "dev";
    assertThrows(Exception.class, dup::persistAndFlush);
  }

  @Test
  @TestTransaction
  void stackWorkspaceIsOneToOne() {
    Organization org = newOrg("acme-workspace-uniq");
    org.persistAndFlush();
    Project project = persistProject(org.id, "proj");
    Workspace workspace = persistWorkspace(org.id);
    persistStack(org.id, project.id, workspace.id, "dev");

    Stack dup = new Stack();
    dup.orgId = org.id;
    dup.projectId = project.id;
    dup.workspaceId = workspace.id;
    dup.name = "prod";
    assertThrows(Exception.class, dup::persistAndFlush);
  }

  @Test
  @TestTransaction
  void stackProjectAndWorkspaceMustShareOrg() {
    Organization orgA = newOrg("acme-cross-org-a");
    orgA.persistAndFlush();
    Organization orgB = newOrg("acme-cross-org-b");
    orgB.persistAndFlush();
    Project project = persistProject(orgA.id, "proj");
    Workspace foreignWorkspace = persistWorkspace(orgB.id);

    // project belongs to orgA, workspace belongs to orgB — the composite FKs
    // must reject this regardless of which org_id we claim on the stack.
    Stack mismatch = new Stack();
    mismatch.orgId = orgA.id;
    mismatch.projectId = project.id;
    mismatch.workspaceId = foreignWorkspace.id;
    mismatch.name = "dev";
    assertThrows(Exception.class, mismatch::persistAndFlush);
  }

  @Test
  @TestTransaction
  void stackStateIsSingletonPerStack() {
    Stack stack = seedTree("acme-state-singleton", "proj", "dev");
    StackState first = new StackState();
    first.stackId = stack.id;
    first.persistAndFlush();

    StackState dup = new StackState();
    dup.stackId = stack.id;
    assertThrows(Exception.class, dup::persistAndFlush);
  }

  @Test
  @TestTransaction
  void workspaceKindIsConstrainedToKnownValues() {
    Organization org = newOrg("acme-kind-check");
    org.persistAndFlush();
    assertThrows(
        Exception.class,
        () ->
            Panache.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO workspaces (id, org_id, kind) VALUES (?, ?, 'helm')")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, org.id)
                .executeUpdate());
  }

  @Test
  @TestTransaction
  void deletingWorkspaceCascadesToStackAndStackState() {
    Stack stack = seedTree("acme-cascade-ws", "proj", "dev");
    StackState state = new StackState();
    state.stackId = stack.id;
    state.persistAndFlush();
    UUID stackId = stack.id;
    UUID workspaceId = stack.workspaceId;

    Workspace.deleteById(workspaceId);
    Workspace.flush();

    // DB-side cascade runs underneath Hibernate's session cache, so findById would still see the
    // cached entity. count() issues fresh SQL.
    assertEquals(0, Stack.count("id", stackId), "stack must cascade with its workspace");
    assertEquals(0, StackState.count("stackId", stackId), "stack_state must cascade with stack");
  }

  @Test
  @TestTransaction
  void deletingOrgCascadesProjectsAndWorkspacesAndStacksAndState() {
    Stack stack = seedTree("acme-cascade-org", "proj", "dev");
    StackState state = new StackState();
    state.stackId = stack.id;
    state.persistAndFlush();
    Project project = Project.findById(stack.projectId);
    UUID orgId = project.orgId;

    Organization.deleteById(orgId);
    Organization.flush();

    assertEquals(0, Project.count("orgId", orgId), "projects must cascade with the org");
    assertEquals(0, Workspace.count("orgId", orgId), "workspaces must cascade with the org");
    assertEquals(0, Stack.count("id", stack.id), "stacks must cascade with the org");
    assertEquals(0, StackState.count("stackId", stack.id), "stack_state must cascade transitively");
  }

  private static Stack seedTree(String orgName, String projectName, String stackName) {
    Organization org = newOrg(orgName);
    org.persistAndFlush();
    Project project = persistProject(org.id, projectName);
    Workspace workspace = persistWorkspace(org.id);
    return persistStack(org.id, project.id, workspace.id, stackName);
  }

  private static Organization newOrg(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    return org;
  }

  private static Project persistProject(UUID orgId, String name) {
    Project p = new Project();
    p.orgId = orgId;
    p.name = name;
    p.persistAndFlush();
    return p;
  }

  private static Workspace persistWorkspace(UUID orgId) {
    Workspace w = new Workspace();
    w.orgId = orgId;
    w.kind = Workspace.Kind.pulumi;
    w.persistAndFlush();
    return w;
  }

  private static Stack persistStack(UUID orgId, UUID projectId, UUID workspaceId, String name) {
    Stack s = new Stack();
    s.orgId = orgId;
    s.projectId = projectId;
    s.workspaceId = workspaceId;
    s.name = name;
    s.persistAndFlush();
    return s;
  }
}
