package io.statedb.persistence.stack;

import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
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

  public List<WorkspaceSummary> findVisibleToUser(UUID userId) {
    return findVisibleToUser(userId, null, null);
  }

  public List<WorkspaceSummary> findVisibleToUser(UUID userId, String org, String project) {
    List<StackSummary> stacks = stackListFinder.findVisibleToUser(userId, org, project);
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

  private Map<UUID, WorkspaceSummary.LastUpdate> latestUpdates(List<UUID> stackIds) {
    Map<UUID, WorkspaceSummary.LastUpdate> latest = new HashMap<>();
    // Correlated subquery keeps this to one row per stack; without it every stack's full update
    // history (jsonb columns included) is hydrated on a list the console polls.
    StackUpdate.<StackUpdate>find(
            "from StackUpdate u where u.stackId in ?1 and u.startedAt = "
                + "(select max(u2.startedAt) from StackUpdate u2 where u2.stackId = u.stackId)",
            stackIds)
        .list()
        .forEach(
            u ->
                latest.putIfAbsent(
                    u.stackId,
                    new WorkspaceSummary.LastUpdate(
                        u.kind.wireValue(), statusWireValue(u.status), u.startedAt, u.version)));
    return latest;
  }

  private Map<UUID, WorkspaceSummary.Lock> liveLocks(List<UUID> stackIds) {
    Instant now = Instant.now();
    Map<UUID, WorkspaceSummary.Lock> locks = new HashMap<>();
    StackLock.<StackLock>find("stackId in ?1 and expiresAt > ?2", stackIds, now)
        .list()
        .forEach(l -> locks.put(l.stackId, new WorkspaceSummary.Lock(l.lockedBy, l.lockedAt)));
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
