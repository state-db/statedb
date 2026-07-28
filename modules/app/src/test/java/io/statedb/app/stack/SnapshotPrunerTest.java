package io.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Retention by journal-entry distance, recency, and import lineage. */
@QuarkusTest
@TestProfile(SnapshotPrunerTest.JournalLadderProfile.class)
class SnapshotPrunerTest extends AbstractSnapshotPruningTest {

  @Override
  String orgName() {
    return "snapshot-pruner-org";
  }

  @Test
  void keepsAnchorLadderRungsAndRecencyAndDropsTheRest() {
    for (int i = 1; i <= 5; i++) {
      updateAndCompact("r" + i);
    }

    // Anchor at 1; ladder spacing 3 promotes 4; recency 2 keeps 4 and 5 (5 is also the live base).
    assertEquals(List.of(1L, 4L, 5L), snapshotBoundaries());
    assertEquals("COMPLETED", latestJob().status.name());
  }

  @Test
  void currentExportIsUnchangedByPruning() {
    updateAndCompact("a");
    updateAndCompact("b");
    JsonNode before = exportHead();

    for (int i = 3; i <= 5; i++) {
      updateAndCompact("r" + i);
    }

    assertTrue(snapshotBoundaries().size() < 5, "pruning must have removed something");
    assertEquals(
        before.required("resources").size() + 3, exportHead().required("resources").size());
  }

  @Test
  void historicalExportFallsBackWhenNearestSnapshotWasPruned() {
    updateAndCompact("a");
    updateAndCompact("b");
    long prunedBoundary = sequence;
    int prunedVersion = updateVersion;

    JsonNode before = exportAt(prunedBoundary, prunedVersion);

    for (int i = 3; i <= 5; i++) {
      updateAndCompact("r" + i);
    }

    assertFalse(snapshotBoundaries().contains(prunedBoundary), "the nearest rung must be gone");
    assertEquals(before, exportAt(prunedBoundary, prunedVersion));
  }

  @Test
  void importLineageBaseSurvivesAndItsVersionedExportIsUnchanged() {
    updateAndCompact("before-import");

    importDeployment("imported");
    long importBoundary = sequence;
    int importVersion = updateVersion;
    JsonNode before = exportAt(importBoundary, importVersion);

    for (int i = 3; i <= 7; i++) {
      updateAndCompact("r" + i);
    }

    // The imported checkpoint is not the fold of the journal prefix, so replaying across it from
    // an older rung would silently drop the replacement. It has to outlive the ladder.
    assertEquals(2, java.util.Collections.frequency(snapshotBoundaries(), importBoundary));
    assertEquals(before, exportAt(importBoundary, importVersion));
  }

  @Test
  void pruningIsIdempotent() {
    for (int i = 1; i <= 5; i++) {
      updateAndCompact("r" + i);
    }
    List<Long> afterFirst = snapshotBoundaries();

    assertEquals(0, pruneAgain(), "a converged ladder must delete nothing");
    assertEquals(afterFirst, snapshotBoundaries());
  }

  public static final class JournalLadderProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "state-db.compaction.prune.newest-snapshots", "2",
          "state-db.compaction.prune.ladder-journal-spacing", "3",
          "state-db.compaction.prune.ladder-update-spacing", "0");
    }
  }
}
