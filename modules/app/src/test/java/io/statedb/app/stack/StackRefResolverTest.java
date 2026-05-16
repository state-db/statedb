package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
import io.statedb.persistence.stack.Workspace;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackRefResolverTest {

  @Inject StackRefResolver resolver;

  @Test
  @TestTransaction
  void resolvesExistingTriple() {
    Organization org = newOrg("acme-resolve");
    org.persistAndFlush();
    Project project = persistProject(org.id, "infra");
    Workspace workspace = persistWorkspace(org.id);
    Stack stack = persistStack(org.id, project.id, workspace.id, "dev");
    UUID activeUpdate = UUID.randomUUID();
    stack.activeUpdateId = activeUpdate;
    stack.version = 7;
    stack.persistAndFlush();

    Optional<StackRef> resolved = resolver.resolve("acme-resolve", "infra", "dev");

    assertTrue(resolved.isPresent(), "expected resolver to find the seeded stack");
    StackRef ref = resolved.get();
    assertEquals(stack.id, ref.stackId());
    assertEquals(workspace.id, ref.workspaceId());
    assertEquals(org.id, ref.orgId());
    assertEquals("acme-resolve", ref.orgName());
    assertEquals("infra", ref.projectName());
    assertEquals("dev", ref.stackName());
    assertEquals(7, ref.version());
    assertEquals(activeUpdate, ref.activeUpdateId());
  }

  @Test
  @TestTransaction
  void missingStackReturnsEmpty() {
    Organization org = newOrg("acme-miss");
    org.persistAndFlush();
    Project project = persistProject(org.id, "infra");
    Workspace workspace = persistWorkspace(org.id);
    persistStack(org.id, project.id, workspace.id, "dev");

    assertEquals(Optional.empty(), resolver.resolve("acme-miss", "infra", "prod"));
    assertEquals(Optional.empty(), resolver.resolve("acme-miss", "unknown-proj", "dev"));
  }

  @Test
  @TestTransaction
  void wrongOrgNameReturnsEmpty() {
    // A stack named (proj, dev) exists in orgA. Querying with orgB's name must
    // return empty even though every name except the org matches — cross-tenant
    // bleed-through is exactly what this resolver is here to prevent.
    Organization orgA = newOrg("acme-tenant-a");
    orgA.persistAndFlush();
    Organization orgB = newOrg("acme-tenant-b");
    orgB.persistAndFlush();
    Project project = persistProject(orgA.id, "proj");
    Workspace workspace = persistWorkspace(orgA.id);
    Stack stack = persistStack(orgA.id, project.id, workspace.id, "dev");
    assertNotNull(stack.id);

    assertEquals(Optional.empty(), resolver.resolve("acme-tenant-b", "proj", "dev"));
    assertTrue(resolver.resolve("acme-tenant-a", "proj", "dev").isPresent());
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
