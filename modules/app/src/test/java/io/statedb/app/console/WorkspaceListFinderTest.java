package io.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Snapshot;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.Workspace;
import io.statedb.persistence.stack.WorkspaceListFinder;
import io.statedb.persistence.stack.WorkspaceSummary;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkspaceListFinderTest {

  @Inject WorkspaceListFinder finder;

  @Inject EntityManager em;

  @Test
  @TestTransaction
  void returnsConsoleWorkspaceRowsForVisibleOrganizationsOnly() {
    Organization visible = org("workspace-visible", "Workspace Visible");
    Organization hidden = org("workspace-hidden", "Workspace Hidden");
    User user = user("workspace-user");
    membership(visible, user);

    Stack prod = stack(visible, "networking", "prod");
    Stack staging = stack(visible, "networking", "staging");
    Stack qa = stack(visible, "networking", "qa");
    stack(hidden, "payments", "prod");
    StackUpdate prodStartedAtMax =
        update(
            prod,
            StackUpdate.Kind.UPDATE,
            StackUpdate.Status.SUCCEEDED,
            14,
            Instant.parse("2026-07-17T10:00:00Z"),
            user);
    update(
        prod,
        StackUpdate.Kind.REFRESH,
        StackUpdate.Status.FAILED,
        13,
        Instant.parse("2026-07-16T10:00:00Z"),
        user);
    StackUpdate prodStartedTie =
        update(
            prod,
            StackUpdate.Kind.DESTROY,
            StackUpdate.Status.CANCELLED,
            15,
            Instant.parse("2026-07-17T10:00:00Z"),
            user);
    update(
        qa,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.SUCCEEDED,
        7,
        Instant.parse("2026-07-17T10:00:00Z"),
        user);
    update(
        qa,
        StackUpdate.Kind.REFRESH,
        StackUpdate.Status.SUCCEEDED,
        8,
        Instant.parse("2026-07-18T10:00:00Z"),
        user);
    em.flush();
    setCreatedAt(prodStartedAtMax, Instant.parse("2026-07-17T10:00:00Z"));
    setCreatedAt(prodStartedTie, Instant.parse("2026-07-17T10:00:01Z"));
    lock(staging, "alice@example.test", Instant.parse("2026-07-17T11:00:00Z"));
    snapshotResources(prod, 2);
    em.flush();
    em.clear();

    List<WorkspaceSummary> rows = finder.findVisibleToUser(user.id);

    assertEquals(3, rows.size());
    WorkspaceSummary prodRow = rows.get(0);
    WorkspaceSummary qaRow = rows.get(1);
    WorkspaceSummary stagingRow = rows.get(2);

    assertEquals("workspace-visible", prodRow.orgName());
    assertEquals("networking", prodRow.projectName());
    assertEquals("prod", prodRow.stackName());
    assertEquals("destroy", prodRow.lastUpdate().kind());
    assertEquals("cancelled", prodRow.lastUpdate().status());
    assertEquals(15, prodRow.lastUpdate().version());
    assertEquals("User workspace-user", prodRow.lastUpdate().actor().displayName());
    assertEquals("workspace-user@example.test", prodRow.lastUpdate().actor().email());
    assertEquals(2L, prodRow.resourceCount());
    assertNull(prodRow.lock());

    assertEquals("qa", qaRow.stackName());
    assertEquals("refresh", qaRow.lastUpdate().kind());
    assertEquals(8, qaRow.lastUpdate().version());

    assertEquals("staging", stagingRow.stackName());
    assertEquals("alice@example.test", stagingRow.lock().lockedBy());
    assertEquals(0L, stagingRow.resourceCount());
    assertNull(stagingRow.lastUpdate());

    assertEquals(
        List.of("prod", "qa", "staging"),
        finder.findVisibleToUser(user.id, "workspace-visible", "networking").stream()
            .map(WorkspaceSummary::stackName)
            .toList());
    assertEquals(
        List.of(), finder.findVisibleToUser(user.id, "workspace-visible", "missing-project"));
    assertEquals(List.of(), finder.findVisibleToUser(user.id, "workspace-hidden", null));
  }

  private static Organization org(String name, String displayName) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = displayName;
    org.persist();
    return org;
  }

  private static User user(String sub) {
    User user = new User();
    user.iss = "https://idp.test";
    user.sub = sub;
    user.email = sub + "@example.test";
    user.displayName = "User " + sub;
    user.persist();
    return user;
  }

  private static void membership(Organization org, User user) {
    OrgMember member = new OrgMember();
    member.orgId = org.id;
    member.userId = user.id;
    member.role = OrgMember.Role.member;
    member.persist();
  }

  private static Stack stack(Organization org, String projectName, String stackName) {
    Project project =
        Project.<Project>find("orgId = ?1 and name = ?2", org.id, projectName)
            .firstResultOptional()
            .orElseGet(
                () -> {
                  Project p = new Project();
                  p.orgId = org.id;
                  p.name = projectName;
                  p.persist();
                  return p;
                });

    Workspace workspace = new Workspace();
    workspace.orgId = org.id;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persist();

    Stack stack = new Stack();
    stack.orgId = org.id;
    stack.projectId = project.id;
    stack.workspaceId = workspace.id;
    stack.name = stackName;
    stack.version = 14;
    stack.persist();

    StackState state = new StackState();
    state.stackId = stack.id;
    state.persist();
    return stack;
  }

  private static StackUpdate update(
      Stack stack,
      StackUpdate.Kind kind,
      StackUpdate.Status status,
      int version,
      Instant startedAt,
      User user) {
    StackUpdate update = new StackUpdate();
    update.stackId = stack.id;
    update.kind = kind;
    update.status = status;
    update.version = version;
    update.startedAt = startedAt;
    update.startedByUserId = user.id;
    update.dryRun = false;
    update.persist();
    return update;
  }

  private void setCreatedAt(StackUpdate update, Instant createdAt) {
    // created_at is Hibernate-managed and immutable on the entity; set it directly for this
    // tie-break fixture.
    em.createNativeQuery("update stack_updates set created_at = ?1 where id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, update.id)
        .executeUpdate();
  }

  private static void lock(Stack stack, String lockedBy, Instant lockedAt) {
    StackLock lock = new StackLock();
    lock.stackId = stack.id;
    lock.lockedBy = lockedBy;
    lock.lockTokenHash = UUID.randomUUID().toString().getBytes();
    lock.lockedAt = lockedAt;
    lock.heartbeatAt = lockedAt;
    lock.expiresAt = Instant.now().plusSeconds(300);
    lock.maxExpiresAt = Instant.now().plusSeconds(600);
    lock.persist();
  }

  private static void snapshotResources(Stack stack, int count) {
    ArrayNode resources = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < count; i++) {
      resources.add(JsonNodeFactory.instance.objectNode().put("urn", "urn:res-" + i));
    }

    Snapshot snapshot = new Snapshot();
    snapshot.stackId = stack.id;
    snapshot.version = 1;
    snapshot.checkpoint = JsonNodeFactory.instance.objectNode().set("resources", resources);
    snapshot.compactedFromJournalSeq = 0L;
    snapshot.resourceCount = count;
    snapshot.persist();

    StackState state = StackState.<StackState>find("stackId", stack.id).singleResult();
    state.baseSnapshotId = snapshot.id;
    state.baseSnapshotVersion = 1;
  }
}
