package io.statedb.persistence.stack;

import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.StartupEvent;
import io.statedb.persistence.update.LineageSnapshotVersion;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.UpdateJournalBoundary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Deletes the snapshots retention no longer needs after a compaction. Survivors are the live base,
 * the lineage bases installed by import/rename, the newest N, and a ladder of older rungs.
 *
 * <p>Pruning loses no information: snapshots are a derived cache of the journal, so {@link
 * StackAssembler} rebuilds any pruned one by replaying from an older retained rung, or from the
 * empty base when none survives below the boundary.
 *
 * <p>Callers must run this inside the compaction transaction. Deleting snapshots needs the same
 * exclusion as writing them, and it comes from {@link CompactionWorker}: the {@code stack_state}
 * row lock plus the refusal to proceed while an update is in flight. Callers must also have flushed
 * {@code stack_state.base_snapshot_id} — the bulk delete below touches only {@code snapshots}, so
 * Hibernate's space-based auto-flush leaves a pending pointer update unwritten and the delete would
 * trip {@code fk_stack_state_base_snapshot} against the previous base.
 */
@ApplicationScoped
public class SnapshotPruner {

  /**
   * Newest snapshots kept per stack, so exports of recent versions stay a single rung load. {@code
   * 0} disables pruning entirely and keeps every snapshot.
   */
  @ConfigProperty(name = "state-db.compaction.prune.newest-snapshots", defaultValue = "10")
  int newestSnapshots;

  /**
   * Minimum journal-entry distance between older ladder rungs; bounds the journal rows a historical
   * export replays. {@code 0} deactivates this bound.
   */
  @ConfigProperty(name = "state-db.compaction.prune.ladder-journal-spacing", defaultValue = "5000")
  long ladderJournalSpacing;

  /**
   * Minimum number of journal-advancing updates between older ladder rungs. Replay rebuilds the
   * whole deployment once per update boundary it crosses, and that cost is invisible to the
   * journal-entry bound: a stack whose updates each touch a few resources reaches the entry budget
   * only after hundreds of rebuilds. {@code 0} deactivates this bound.
   */
  @ConfigProperty(name = "state-db.compaction.prune.ladder-update-spacing", defaultValue = "50")
  int ladderUpdateSpacing;

  void validateConfig(@Observes StartupEvent event) {
    if (newestSnapshots < 0 || ladderJournalSpacing < 0 || ladderUpdateSpacing < 0) {
      throw new IllegalStateException(
          "state-db.compaction.prune.{newest-snapshots,ladder-journal-spacing,"
              + "ladder-update-spacing} must be >= 0");
    }
  }

  public int prune(UUID stackId, UUID liveBaseSnapshotId) {
    if (newestSnapshots == 0) {
      return 0;
    }

    List<SnapshotRetentionRow> snapshots =
        Snapshot.<Snapshot>find(
                "stackId", Sort.ascending("compactedFromJournalSeq", "version"), stackId)
            .project(SnapshotRetentionRow.class)
            .list();
    if (snapshots.isEmpty()) {
      return 0;
    }

    Set<UUID> keep = new HashSet<>();
    if (liveBaseSnapshotId != null) {
      keep.add(liveBaseSnapshotId);
    }
    keepLineageBases(keep, stackId, snapshots);
    keepNewest(keep, snapshots);
    keepLadderRungs(keep, stackId, snapshots);

    List<UUID> deleteIds =
        snapshots.stream().map(SnapshotRetentionRow::id).filter(id -> !keep.contains(id)).toList();
    if (deleteIds.isEmpty()) {
      return 0;
    }
    return (int) Snapshot.delete("stackId = ?1 and id in ?2", stackId, deleteIds);
  }

  /**
   * Import and rename install snapshots whose content is not the fold of the journal prefix.
   * Replaying across one from an older rung would silently miss the state replacement, so they are
   * lineage bases, never derived caches.
   */
  private void keepLineageBases(
      Set<UUID> keep, UUID stackId, List<SnapshotRetentionRow> snapshots) {
    Set<Integer> lineageVersions =
        StackUpdate.<StackUpdate>find("stackId = ?1 and baseSnapshotVersion is not null", stackId)
            .project(LineageSnapshotVersion.class)
            .list()
            .stream()
            .map(LineageSnapshotVersion::baseSnapshotVersion)
            .collect(Collectors.toSet());
    snapshots.stream()
        .filter(snapshot -> lineageVersions.contains(snapshot.version()))
        .forEach(snapshot -> keep.add(snapshot.id()));
  }

  private void keepNewest(Set<UUID> keep, List<SnapshotRetentionRow> snapshots) {
    // The query ordered them oldest-first, so the newest are the tail.
    int from = Math.max(0, snapshots.size() - newestSnapshots);
    snapshots.subList(from, snapshots.size()).forEach(snapshot -> keep.add(snapshot.id()));
  }

  /**
   * Greedy walk from the oldest snapshot, keeping a rung once either spacing budget is spent. The
   * two bounds cap the two terms of replay cost — rows replayed, and whole-deployment rebuilds —
   * which dominate on opposite workload shapes: a stack that rewrites every resource each update
   * spends the entry budget within a handful of updates, while a stack making small changes spends
   * hundreds of updates reaching it.
   *
   * <p>Walking from the oldest keeps the ladder deterministic: every rung it kept last time is
   * still present, so the same rungs are reproduced from the survivors alone.
   */
  private void keepLadderRungs(Set<UUID> keep, UUID stackId, List<SnapshotRetentionRow> snapshots) {
    if (ladderJournalSpacing == 0 && ladderUpdateSpacing == 0) {
      return;
    }

    SnapshotRetentionRow anchor = snapshots.getFirst();
    keep.add(anchor.id());

    long[] boundaries = ladderUpdateSpacing == 0 ? null : updateBoundaries(stackId);
    long survivorSeq = anchor.compactedFromJournalSeq();
    int survivorOrdinal = ordinalOf(boundaries, survivorSeq);

    for (SnapshotRetentionRow snapshot : snapshots.subList(1, snapshots.size())) {
      long seq = snapshot.compactedFromJournalSeq();
      int ordinal = ordinalOf(boundaries, seq);
      boolean journalBudgetSpent =
          ladderJournalSpacing > 0 && seq - survivorSeq >= ladderJournalSpacing;
      boolean updateBudgetSpent =
          ladderUpdateSpacing > 0 && ordinal - survivorOrdinal >= ladderUpdateSpacing;
      if (journalBudgetSpent || updateBudgetSpent) {
        keep.add(snapshot.id());
        survivorSeq = seq;
        survivorOrdinal = ordinal;
      }
    }
  }

  /**
   * Distinct {@code end_journal_seq} values of this stack's terminal updates, ascending. Distinct
   * because an update that journals nothing shares its predecessor's boundary and costs replay no
   * rebuild, so the count between two rungs is exactly the number of rebuilds a replay across them
   * performs.
   */
  private long[] updateBoundaries(UUID stackId) {
    return StackUpdate.<StackUpdate>find(
            "stackId = ?1 and endJournalSeq is not null", Sort.ascending("endJournalSeq"), stackId)
        .project(UpdateJournalBoundary.class)
        .list()
        .stream()
        .mapToLong(UpdateJournalBoundary::endJournalSeq)
        .distinct()
        .toArray();
  }

  private static int ordinalOf(long[] boundaries, long seq) {
    if (boundaries == null) {
      return 0;
    }
    int index = Arrays.binarySearch(boundaries, seq);
    // Every snapshot boundary is a terminal update's end_journal_seq; the insertion point covers
    // a boundary written before its update row reached terminal status.
    return index >= 0 ? index : -(index + 1);
  }
}
