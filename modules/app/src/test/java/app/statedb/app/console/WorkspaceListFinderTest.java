package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.ServiceAccountCreator;
import app.statedb.persistence.identity.ServiceAccountRevoker;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Snapshot;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.stack.WorkspaceListFinder;
import app.statedb.persistence.stack.WorkspaceSummary;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
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

  @Inject ServiceAccountCreator serviceAccountCreator;

  @Inject ServiceAccountRevoker serviceAccountRevoker;

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

  @Test
  @TestTransaction
  void paginatesSearchesAndFiltersByStatus() {
    Organization org = org("workspace-page-org", "Workspace Page Org");
    User user = user("workspace-page-user");
    membership(org, user);

    Stack alpha = stack(org, "svc", "alpha");
    Stack beta = stack(org, "svc", "beta");
    Stack gamma = stack(org, "svc", "gamma");
    update(
        alpha,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.SUCCEEDED,
        1,
        Instant.parse("2026-07-17T10:00:00Z"),
        user);
    update(
        beta,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.FAILED,
        1,
        Instant.parse("2026-07-17T10:00:00Z"),
        user);
    update(
        gamma,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.IN_PROGRESS,
        1,
        Instant.parse("2026-07-17T10:00:00Z"),
        user);
    em.flush();
    em.clear();

    WorkspaceListFinder.PagedWorkspaces page1 =
        finder.findVisibleToUserPaged(user.id, org.name, null, null, 1, 2);
    assertEquals(3, page1.total());
    assertEquals(
        List.of("alpha", "beta"), page1.items().stream().map(WorkspaceSummary::stackName).toList());

    WorkspaceListFinder.PagedWorkspaces page2 =
        finder.findVisibleToUserPaged(user.id, org.name, null, null, 2, 2);
    assertEquals(3, page2.total());
    assertEquals(2, page2.page());
    assertEquals(
        List.of("gamma"), page2.items().stream().map(WorkspaceSummary::stackName).toList());

    WorkspaceListFinder.PagedWorkspaces lastPage =
        finder.findVisibleToUserPaged(user.id, org.name, null, null, 99, 2);
    assertEquals(2, lastPage.page());
    assertEquals(
        List.of("gamma"), lastPage.items().stream().map(WorkspaceSummary::stackName).toList());

    WorkspaceListFinder.PagedWorkspaces failedOnly =
        finder.findVisibleToUserPaged(user.id, org.name, null, "failed", 1, 50);
    assertEquals(
        List.of("beta"), failedOnly.items().stream().map(WorkspaceSummary::stackName).toList());

    WorkspaceListFinder.PagedWorkspaces runningOnly =
        finder.findVisibleToUserPaged(user.id, org.name, null, "running", 1, 50);
    assertEquals(
        List.of("gamma"), runningOnly.items().stream().map(WorkspaceSummary::stackName).toList());

    WorkspaceListFinder.PagedWorkspaces searched =
        finder.findVisibleToUserPaged(user.id, org.name, "bet", null, 1, 50);
    assertEquals(
        List.of("beta"), searched.items().stream().map(WorkspaceSummary::stackName).toList());

    WorkspaceListFinder.WorkspaceOverview overview = finder.overview(user.id, org.name);
    assertEquals(3, overview.stacks());
    assertEquals(1, overview.projects());
    assertEquals(1, overview.failed());
    assertEquals(1, overview.running());
  }

  @Test
  @TestTransaction
  void retainsStableAttributionWhenAServiceTokenIsRevokedAndItsNameIsReused() {
    Organization org = org("workspace-service-actor", "Workspace Service Actor");
    User viewer = user("workspace-service-viewer");
    membership(org, viewer);
    Stack stack = stack(org, "migration", "dev");

    var first =
        serviceAccountCreator.create(
            org.id, "migration-bot", 30, ApiToken.AccessProfile.stack_lifecycle, null);
    ApiToken firstToken = ApiToken.find("userId", first.accountId()).singleResult();
    StackUpdate update = new StackUpdate();
    update.stackId = stack.id;
    update.kind = StackUpdate.Kind.IMPORT;
    update.status = StackUpdate.Status.SUCCEEDED;
    update.version = 1;
    update.startedAt = Instant.parse("2026-07-20T10:00:00Z");
    update.startedByUserId = first.accountId();
    update.startedByApiTokenId = firstToken.id;
    update.dryRun = false;
    update.persistAndFlush();
    serviceAccountRevoker.revoke(org.id, first.accountId(), null);

    var replacement = serviceAccountCreator.create(org.id, "migration-bot", 30, null);
    ApiToken replacementToken = ApiToken.find("userId", replacement.accountId()).singleResult();
    em.flush();
    em.clear();

    var actor = finder.detailsFor(stackSummary(stack)).lastUpdate().actor();
    assertEquals("service", actor.kind());
    assertEquals("migration-bot", actor.displayName());
    assertEquals(first.accountId(), actor.principalId());
    assertEquals(firstToken.id, actor.credentialId());
    assertEquals("stack-lifecycle", actor.accessProfile());
    assertEquals(true, actor.revoked());
    assertNotEquals(replacement.accountId(), actor.principalId());
    assertNotEquals(replacementToken.id, actor.credentialId());
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

  private static app.statedb.persistence.stack.StackSummary stackSummary(Stack stack) {
    Project project = Project.findById(stack.projectId);
    Organization org = Organization.findById(stack.orgId);
    return new app.statedb.persistence.stack.StackSummary(
        stack.id,
        org.id,
        org.name,
        project.name,
        project.createdAt,
        stack.name,
        Workspace.Kind.pulumi,
        stack.version,
        null,
        null);
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
