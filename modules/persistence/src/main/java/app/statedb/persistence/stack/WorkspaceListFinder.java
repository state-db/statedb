package app.statedb.persistence.stack;

import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.UpdateActorAttribution;
import app.statedb.persistence.update.UpdateActorAttributionFinder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

  @Inject WorkspaceDirectoryFinder directoryFinder;

  @Inject UpdateActorAttributionFinder actorAttributionFinder;

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
    return directoryFinder.find(userId, org).overview();
  }

  private List<WorkspaceSummary> assemble(List<StackSummary> stacks) {
    if (stacks.isEmpty()) {
      return List.of();
    }

    List<UUID> stackIds = stacks.stream().map(StackSummary::stackId).toList();
    Map<UUID, WorkspaceSummary.LastUpdate> updates = latestUpdates(stackIds);
    Map<UUID, WorkspaceSummary.Lock> locks = liveLocks(stackIds);
    Map<UUID, Long> resourceCounts = resourceCounts(stackIds);
    java.util.Set<UUID> failedStacks = failedAppliedStacks(stackIds);

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
                    locks.get(s.stackId()),
                    failedStacks.contains(s.stackId())))
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
    Map<UUID, UpdateActorAttribution> actors = actorAttributionFinder.forUpdates(updates);
    latestRows
        .values()
        .forEach(
            u ->
                latest.put(
                    u.stackId,
                    new WorkspaceSummary.LastUpdate(
                        u.id,
                        u.kind.wireValue(),
                        statusWireValue(u.status),
                        u.startedAt,
                        u.dryRun ? null : u.version,
                        actors.get(u.id))));
    return latest;
  }

  private java.util.Set<UUID> failedAppliedStacks(List<UUID> stackIds) {
    List<StackUpdate> applied =
        StackUpdate.<StackUpdate>find(
                "from StackUpdate u where u.stackId in ?1 and u.dryRun = false and (u.stackId, u.version) in "
                    + "(select u2.stackId, max(u2.version) from StackUpdate u2 "
                    + "where u2.stackId in ?1 and u2.dryRun = false group by u2.stackId)",
                stackIds)
            .list();
    java.util.Set<UUID> failed = new java.util.HashSet<>();
    for (StackUpdate u : applied) {
      if (u.status == StackUpdate.Status.FAILED) {
        failed.add(u.stackId);
      }
    }
    return failed;
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
