package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Snapshot;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.stack.WorkspaceDirectoryFinder;
import app.statedb.persistence.update.RecentUpdatesFinder;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkspaceDirectoryAndRecentUpdatesTest {

  @Inject WorkspaceDirectoryFinder directoryFinder;
  @Inject RecentUpdatesFinder recentUpdatesFinder;
  @Inject EntityManager em;

  @Test
  @TestTransaction
  void directoryAggregatesProjectsByVcsRepoAndFolderAndRespectsVisibility() {
    Organization visible = org("dir-visible", "Dir Visible");
    Organization hidden = org("dir-hidden", "Dir Hidden");
    User member = user("dir-member");
    membership(visible, member, OrgMember.Role.member);

    Stack vpcProd =
        stack(
            visible,
            "vpc-core",
            "prod",
            Stack.VISIBILITY_INTERNAL,
            Map.of(
                "vcs:kind", "github.com",
                "vcs:owner", "acme",
                "vcs:repo", "platform-infra",
                "vcs:root", "./infra/network/"));
    Stack vpcDev = stack(visible, "vpc-core", "dev", Stack.VISIBILITY_INTERNAL, Map.of());
    Stack rootProj =
        stack(
            visible,
            "warehouse",
            "prod",
            Stack.VISIBILITY_INTERNAL,
            Map.of("gitHub:owner", "acme", "gitHub:repo", "data-warehouse", "vcs:root", "."));
    Stack standalone = stack(visible, "sandbox", "local", Stack.VISIBILITY_INTERNAL, Map.of());
    Stack privateHidden = stack(visible, "secret-proj", "prod", Stack.VISIBILITY_PRIVATE, Map.of());
    Stack privateShared = stack(visible, "shared-proj", "prod", Stack.VISIBILITY_PRIVATE, Map.of());
    grantRead(visible, member, privateShared);
    stack(hidden, "other-proj", "prod", Stack.VISIBILITY_INTERNAL, Map.of());

    snapshotResources(vpcProd, 12);
    snapshotResources(vpcDev, 5);
    snapshotResources(rootProj, 8);
    snapshotResources(privateHidden, 99);
    snapshotResources(privateShared, 3);

    update(
        vpcProd,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.IN_PROGRESS,
        false,
        2,
        Instant.parse("2026-08-01T12:00:00Z"),
        member,
        null);
    update(
        rootProj,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.FAILED,
        false,
        4,
        Instant.parse("2026-08-01T11:00:00Z"),
        member,
        null);
    // Failed preview does not mark the stack/project as failed
    update(
        vpcDev,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.SUCCEEDED,
        false,
        1,
        Instant.parse("2026-08-01T10:00:00Z"),
        member,
        null);
    update(
        vpcDev,
        StackUpdate.Kind.PREVIEW,
        StackUpdate.Status.FAILED,
        true,
        0,
        Instant.parse("2026-08-01T11:30:00Z"),
        member,
        null);
    update(
        privateShared,
        StackUpdate.Kind.PREVIEW,
        StackUpdate.Status.FAILED,
        true,
        0,
        Instant.parse("2026-08-01T11:45:00Z"),
        member,
        null);
    lock(standalone, "local-cli", Instant.parse("2026-08-01T12:05:00Z"));
    em.flush();
    em.clear();

    WorkspaceDirectoryFinder.DirectoryResult result = directoryFinder.find(member.id, null);

    assertEquals(4, result.overview().projects());
    assertEquals(5, result.overview().stacks());
    assertEquals(28, result.overview().resources());
    assertEquals(2, result.overview().running());
    assertEquals(1, result.overview().failed());

    List<WorkspaceDirectoryFinder.ProjectDirectoryEntry> entries = result.projects();
    assertEquals(4, entries.size());

    // Ordered by vcs_owner, vcs_repo, vcs_root NULLS FIRST, then standalone (NULLS LAST)
    var warehouse = entries.get(0);
    assertEquals("warehouse", warehouse.project());
    assertEquals("github.com", warehouse.vcsKind());
    assertEquals("acme", warehouse.vcsOwner());
    assertEquals("data-warehouse", warehouse.vcsRepo());
    assertNull(warehouse.vcsRoot());
    assertEquals(1, warehouse.failedCount());

    var vpc = entries.get(1);
    assertEquals("vpc-core", vpc.project());
    assertEquals("github.com", vpc.vcsKind());
    assertEquals("acme", vpc.vcsOwner());
    assertEquals("platform-infra", vpc.vcsRepo());
    assertEquals("infra/network", vpc.vcsRoot());
    assertEquals(2, vpc.stackCount());
    assertEquals(17, vpc.resourceCount());
    assertEquals(1, vpc.runningCount());

    var sandbox = entries.get(2);
    assertEquals("sandbox", sandbox.project());
    assertNull(sandbox.vcsOwner());
    assertNull(sandbox.vcsRepo());
    assertEquals(1, sandbox.runningCount());

    var shared = entries.get(3);
    assertEquals("shared-proj", shared.project());
    assertEquals(3, shared.resourceCount());

    assertTrue(directoryFinder.isOrganizationVisible(member.id, "dir-visible"));
    assertFalse(directoryFinder.isOrganizationVisible(member.id, "dir-hidden"));
  }

  @Test
  @TestTransaction
  void recentUpdatesPrioritizesRunningAndHidesPreviewsByDefault() {
    Organization org = org("recent-org", "Recent Org");
    Organization otherOrg = org("recent-other", "Recent Other");
    User user = user("recent-user");
    membership(org, user, OrgMember.Role.owner);

    Stack stack = stack(org, "infra", "prod", Stack.VISIBILITY_INTERNAL, Map.of());
    Stack hiddenStack = stack(otherOrg, "hidden", "prod", Stack.VISIBILITY_INTERNAL, Map.of());

    StackUpdate appliedOld =
        update(
            stack,
            StackUpdate.Kind.UPDATE,
            StackUpdate.Status.SUCCEEDED,
            false,
            1,
            Instant.parse("2026-08-01T10:00:00Z"),
            user,
            null);
    StackUpdate preview =
        update(
            stack,
            StackUpdate.Kind.PREVIEW,
            StackUpdate.Status.SUCCEEDED,
            true,
            0,
            Instant.parse("2026-08-01T11:00:00Z"),
            user,
            Map.of("environment", Map.of("ci.pr.number", "418")));
    StackUpdate failed =
        update(
            stack,
            StackUpdate.Kind.UPDATE,
            StackUpdate.Status.FAILED,
            false,
            2,
            Instant.parse("2026-08-01T11:30:00Z"),
            user,
            null);
    StackUpdate running =
        update(
            stack,
            StackUpdate.Kind.UPDATE,
            StackUpdate.Status.IN_PROGRESS,
            false,
            3,
            Instant.parse("2026-08-01T12:00:00Z"),
            user,
            Map.of("environment", Map.of("ci.pr.number", "419")));
    StackUpdate runningPreview =
        update(
            stack,
            StackUpdate.Kind.PREVIEW,
            StackUpdate.Status.IN_PROGRESS,
            true,
            0,
            Instant.parse("2026-08-01T12:01:00Z"),
            user,
            null);
    update(
        hiddenStack,
        StackUpdate.Kind.UPDATE,
        StackUpdate.Status.SUCCEEDED,
        false,
        1,
        Instant.parse("2026-08-01T12:05:00Z"),
        user,
        null);
    em.flush();
    em.clear();

    // Default: previews hidden
    var withoutPreviews = recentUpdatesFinder.find(user.id, null, false, null, 25);
    assertEquals(List.of(running.id), withoutPreviews.running().stream().map(r -> r.id()).toList());
    assertEquals(419L, withoutPreviews.running().getFirst().prNumber());
    assertEquals(
        List.of(failed.id, appliedOld.id),
        withoutPreviews.recent().stream().map(r -> r.id()).toList());

    // Previews toggled on
    var withPreviews = recentUpdatesFinder.find(user.id, null, true, null, 25);
    assertEquals(
        List.of(runningPreview.id, running.id),
        withPreviews.running().stream().map(r -> r.id()).toList());
    assertEquals(
        List.of(failed.id, preview.id, appliedOld.id),
        withPreviews.recent().stream().map(r -> r.id()).toList());
    assertEquals(418L, withPreviews.recent().get(1).prNumber());
    assertNull(withPreviews.recent().get(1).version());

    // Failed filter
    var failedOnly = recentUpdatesFinder.find(user.id, "recent-org", false, "failed", 25);
    assertTrue(failedOnly.running().isEmpty());
    assertEquals(List.of(failed.id), failedOnly.recent().stream().map(r -> r.id()).toList());
  }

  private Organization org(String name, String displayName) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = displayName;
    org.persist();
    return org;
  }

  private User user(String sub) {
    User user = new User();
    user.iss = "https://idp.example.test";
    user.sub = sub;
    user.email = sub + "@example.test";
    user.displayName = "User " + sub;
    user.persist();
    return user;
  }

  private void membership(Organization org, User user, OrgMember.Role role) {
    OrgMember member = new OrgMember();
    member.orgId = org.id;
    member.userId = user.id;
    member.role = role;
    member.persist();
  }

  private void grantRead(Organization org, User user, Stack stack) {
    PolicyGrant grant = new PolicyGrant();
    grant.orgId = org.id;
    grant.principalUserId = user.id;
    grant.resourceStackId = stack.id;
    grant.templateId = "StackReadResourcePolicy";
    grant.persist();
  }

  private Stack stack(
      Organization org,
      String projectName,
      String stackName,
      String visibility,
      Map<String, String> tags) {
    Project project =
        Project.<Project>find("orgId = ?1 and name = ?2", org.id, projectName).firstResult();
    if (project == null) {
      project = new Project();
      project.orgId = org.id;
      project.name = projectName;
      project.persist();
    }
    Workspace workspace = new Workspace();
    workspace.orgId = org.id;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persist();

    Stack stack = new Stack();
    stack.orgId = org.id;
    stack.projectId = project.id;
    stack.workspaceId = workspace.id;
    stack.name = stackName;
    stack.visibility = visibility;
    stack.tags = tags;
    stack.persist();

    StackState state = new StackState();
    state.stackId = stack.id;
    state.persist();
    return stack;
  }

  private void snapshotResources(Stack stack, int count) {
    ArrayNode resources = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < count; i++) {
      resources.addObject().put("urn", "urn:pulumi:dev::proj::custom:r::r" + i);
    }
    Snapshot snapshot = new Snapshot();
    snapshot.stackId = stack.id;
    snapshot.version = 1;
    snapshot.compactedFromJournalSeq = 0;
    snapshot.resourceCount = count;
    snapshot.checkpoint = JsonNodeFactory.instance.objectNode().set("resources", resources);
    snapshot.persist();

    StackState state = StackState.<StackState>find("stackId", stack.id).singleResult();
    state.baseSnapshotId = snapshot.id;
    state.baseSnapshotVersion = 1;
  }

  private StackUpdate update(
      Stack stack,
      StackUpdate.Kind kind,
      StackUpdate.Status status,
      boolean dryRun,
      int version,
      Instant startedAt,
      User actor,
      Map<String, Object> metadata) {
    StackUpdate update = new StackUpdate();
    update.stackId = stack.id;
    update.kind = kind;
    update.status = status;
    update.dryRun = dryRun;
    update.version = version;
    update.startedAt = startedAt;
    update.startedByUserId = actor.id;
    update.metadata = metadata;
    update.persist();
    return update;
  }

  private void lock(Stack stack, String lockedBy, Instant lockedAt) {
    StackLock lock = new StackLock();
    lock.stackId = stack.id;
    lock.lockedBy = lockedBy;
    lock.lockTokenHash = UUID.randomUUID().toString().getBytes();
    lock.lockedAt = lockedAt;
    lock.expiresAt = Instant.now().plusSeconds(300);
    lock.maxExpiresAt = Instant.now().plusSeconds(3600);
    lock.heartbeatAt = lockedAt;
    lock.persist();
  }
}
