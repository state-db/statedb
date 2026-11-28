package app.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.Workspace;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Schema-contract tests for {@code policy_grants}: a row must be a known template with the matching
 * {@code ?resource} shape, and a per-stack share must point at a stack inside the grant's own org.
 * These DB invariants keep PolicySetCache from ever seeing a row it can't turn into a valid (and
 * correctly tenanted) Cedar TemplateLink.
 */
@QuarkusTest
class PolicyGrantConstraintsTest extends DatabaseTest {

  @Test
  @TestTransaction
  void validManagedAndShareGrantsAreAccepted() {
    Fixture f = seed("grant-ok");
    grant("StackWriteAccess", f.orgId, f.userId, null).persistAndFlush();
    grant("StackReadResourcePolicy", f.orgId, f.userId, f.stackId).persistAndFlush();
    grant("StackWriteResourcePolicy", f.orgId, f.userId, f.stackId).persistAndFlush();
  }

  @Test
  @TestTransaction
  void unknownTemplateIsRejected() {
    Fixture f = seed("grant-bad-template");
    PolicyGrant g = grant("not-a-template", f.orgId, f.userId, null);
    assertThrows(Exception.class, g::persistAndFlush);
  }

  @Test
  @TestTransaction
  void managedGrantWithAStackIsRejected() {
    Fixture f = seed("grant-managed-stack");
    PolicyGrant g = grant("StackWriteAccess", f.orgId, f.userId, f.stackId);
    assertThrows(Exception.class, g::persistAndFlush);
  }

  @Test
  @TestTransaction
  void shareGrantWithoutAStackIsRejected() {
    Fixture f = seed("grant-share-nostack");
    PolicyGrant g = grant("StackWriteResourcePolicy", f.orgId, f.userId, null);
    assertThrows(Exception.class, g::persistAndFlush);
  }

  @Test
  @TestTransaction
  void shareStackMustBelongToTheGrantOrg() {
    Fixture f = seed("grant-cross-org");
    Organization otherOrg = newOrg("grant-cross-org-other");
    otherOrg.persistAndFlush();
    // The stack belongs to f.orgId, but the grant is tenanted under otherOrg — the
    // composite FK must reject binding ?resource to another org's stack.
    PolicyGrant g = grant("StackWriteResourcePolicy", otherOrg.id, f.userId, f.stackId);
    assertThrows(Exception.class, g::persistAndFlush);
  }

  private static PolicyGrant grant(String templateId, UUID orgId, UUID userId, UUID stackId) {
    PolicyGrant g = new PolicyGrant();
    g.templateId = templateId;
    g.orgId = orgId;
    g.principalUserId = userId;
    g.resourceStackId = stackId;
    return g;
  }

  private record Fixture(UUID orgId, UUID userId, UUID stackId) {}

  private static Fixture seed(String prefix) {
    Organization org = newOrg(prefix);
    org.persistAndFlush();

    User user = new User();
    user.iss = "local:test";
    user.sub = prefix + "-user";
    user.email = prefix + "@example.com";
    user.displayName = prefix;
    user.persistAndFlush();

    Project project = new Project();
    project.orgId = org.id;
    project.name = "proj";
    project.persistAndFlush();

    Workspace workspace = new Workspace();
    workspace.orgId = org.id;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persistAndFlush();

    Stack stack = new Stack();
    stack.orgId = org.id;
    stack.projectId = project.id;
    stack.workspaceId = workspace.id;
    stack.name = "prod";
    stack.persistAndFlush();

    return new Fixture(org.id, user.id, stack.id);
  }

  private static Organization newOrg(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    return org;
  }
}
