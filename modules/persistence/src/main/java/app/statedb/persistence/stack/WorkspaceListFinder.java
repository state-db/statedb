package app.statedb.persistence.stack;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lists console workspace rows readable by a principal.
 *
 * <p>The base stack tree comes from {@link StackListFinder} (which owns the read-visibility
 * predicate), keeping tenant-isolation joins in one place. Optional status details are batch-loaded
 * by stack id so this endpoint can grow without changing Pulumi's own list DTO shape.
 *
 * <p>Resource counts come from the base snapshot's materialized {@code resource_count}, so listing
 * never assembles a checkpoint. A snapshot is installed after every completed update, import, and
 * rename; a stack with no snapshot yet has no completed update and counts as zero.
 */
@ApplicationScoped
public class WorkspaceListFinder {

  @Inject StackListFinder stackListFinder;

  public List<WorkspaceSummary> findVisibleToUser(UUID userId) {
    return findVisibleToUser(userId, null, null);
  }

  public List<WorkspaceSummary> findVisibleToUser(UUID userId, String org, String project) {
    return assemble(stackListFinder.findVisibleToUser(userId, org, project));
  }

  public PagedWorkspaces findVisibleToUserPaged(
      UUID userId, String org, String search, String status, int page, int pageSize) {
    StackListFinder.PagedStacks paged =
        stackListFinder.findVisibleToUserPaged(userId, org, search, status, page, pageSize);
    return new PagedWorkspaces(assemble(paged.items()), paged.total(), paged.page());
  }

  /** Adds console status details to one stack row already authorized by {@link StackListFinder}. */
  public WorkspaceSummary detailsFor(StackSummary stack) {
    return assemble(List.of(stack)).get(0);
  }

  public WorkspaceOverview overview(UUID userId, String org) {
    List<WorkspaceSummary> all = findVisibleToUser(userId, org, null);
    long projects = all.stream().map(w -> w.orgName() + "/" + w.projectName()).distinct().count();
    long resources =
        all.stream().mapToLong(w -> w.resourceCount() == null ? 0L : w.resourceCount()).sum();
    long failed =
        all.stream()
            .filter(w -> w.lastUpdate() != null && "failed".equals(w.lastUpdate().status()))
            .count();
    long running = all.stream().filter(WorkspaceListFinder::isActive).count();
    return new WorkspaceOverview(all.size(), projects, resources, failed, running);
  }

  private static boolean isActive(WorkspaceSummary w) {
    return w.lock() != null
        || (w.lastUpdate() != null && "in-progress".equals(w.lastUpdate().status()));
  }

  private List<WorkspaceSummary> assemble(List<StackSummary> stacks) {
    if (stacks.isEmpty()) {
      return List.of();
    }

    List<UUID> stackIds = stacks.stream().map(StackSummary::stackId).toList();
    Map<UUID, WorkspaceSummary.LastUpdate> updates = latestUpdates(stackIds);
    Map<UUID, WorkspaceSummary.Lock> locks = liveLocks(stackIds);
    Map<UUID, Long> resourceCounts = resourceCounts(stackIds);

    return stacks.stream()
        .map(
            s ->
                new WorkspaceSummary(
                    s.stackId(),
                    s.orgName(),
                    s.projectName(),
                    s.projectCreatedAt(),
                    s.stackName(),
                    s.workspaceKind(),
                    s.version(),
                    resourceCounts.getOrDefault(s.stackId(), 0L),
                    updates.get(s.stackId()),
                    locks.get(s.stackId())))
        .toList();
  }

  public record PagedWorkspaces(List<WorkspaceSummary> items, long total, int page) {}

  public record WorkspaceOverview(
      long stacks, long projects, long resources, long failed, long running) {}

  private Map<UUID, WorkspaceSummary.LastUpdate> latestUpdates(List<UUID> stackIds) {
    // Compute the latest timestamp once per visible stack, then load only those update rows.
    List<StackUpdate> updates =
        StackUpdate.<StackUpdate>find(
                "from StackUpdate u where u.stackId in ?1 and (u.stackId, u.startedAt) in "
                    + "(select u2.stackId, max(u2.startedAt) from StackUpdate u2 "
                    + "where u2.stackId in ?1 group by u2.stackId)",
                stackIds)
            .list();
    Map<UUID, StackUpdate> latestRows = new HashMap<>();
    updates.forEach(
        u ->
            latestRows.merge(
                u.stackId,
                u,
                (existing, candidate) ->
                    candidate.createdAt.isAfter(existing.createdAt) ? candidate : existing));

    Map<UUID, WorkspaceSummary.LastUpdate> latest = new HashMap<>();
    Map<UUID, User> users = usersById(updates);
    Map<UUID, ApiToken> tokens = tokensById(updates);
    latestRows
        .values()
        .forEach(
            u ->
                latest.put(
                    u.stackId,
                    new WorkspaceSummary.LastUpdate(
                        u.kind.wireValue(),
                        statusWireValue(u.status),
                        u.startedAt,
                        u.version,
                        actorFor(
                            users.get(u.startedByUserId), tokens.get(u.startedByApiTokenId)))));
    return latest;
  }

  private Map<UUID, User> usersById(List<StackUpdate> updates) {
    List<UUID> userIds =
        updates.stream().map(u -> u.startedByUserId).filter(Objects::nonNull).distinct().toList();
    if (userIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return User.<User>list("id in ?1", userIds).stream()
        .collect(Collectors.toMap(user -> user.id, Function.identity()));
  }

  private Map<UUID, ApiToken> tokensById(List<StackUpdate> updates) {
    List<UUID> tokenIds =
        updates.stream()
            .map(u -> u.startedByApiTokenId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (tokenIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return ApiToken.<ApiToken>list("id in ?1", tokenIds).stream()
        .collect(Collectors.toMap(token -> token.id, Function.identity()));
  }

  private WorkspaceSummary.Actor actorFor(User user, ApiToken token) {
    if (user == null && token == null) {
      return null;
    }
    String fallback = user != null ? user.iss + ":" + user.sub : "unknown actor";
    return new WorkspaceSummary.Actor(
        user != null ? user.displayName : null, user != null ? user.email : null, fallback);
  }

  private Map<UUID, WorkspaceSummary.Lock> liveLocks(List<UUID> stackIds) {
    Map<UUID, WorkspaceSummary.Lock> locks = new HashMap<>();
    StackLock.<StackLock>find("stackId in ?1 and expiresAt > current_timestamp", stackIds)
        .list()
        .forEach(
            l -> locks.put(l.stackId, new WorkspaceSummary.Lock(l.id, l.lockedBy, l.lockedAt)));
    return locks;
  }

  private Map<UUID, Long> resourceCounts(List<UUID> stackIds) {
    List<UUID> snapshotIds =
        StackState.<StackState>list("stackId in ?1 and baseSnapshotId is not null", stackIds)
            .stream()
            .map(state -> state.baseSnapshotId)
            .toList();
    if (snapshotIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> counts = new HashMap<>();
    Snapshot.find("id in ?1", snapshotIds)
        .project(SnapshotResourceCount.class)
        .list()
        .forEach(c -> counts.put(c.stackId(), (long) c.resourceCount()));
    return counts;
  }

  private static String statusWireValue(StackUpdate.Status status) {
    return switch (status) {
      case IN_PROGRESS -> "in-progress";
      case SUCCEEDED -> "succeeded";
      case FAILED -> "failed";
      case CANCELLED -> "cancelled";
    };
  }
}
