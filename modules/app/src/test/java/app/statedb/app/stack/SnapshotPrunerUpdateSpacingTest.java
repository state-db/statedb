package app.statedb.app.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.stack.CompactionJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Retention by update distance — the bound the journal-entry spacing cannot see. Every update here
 * journals a single entry, so the entry budget is never spent and only the update budget promotes a
 * rung.
 *
 * <p>{@code newest-snapshots: 1} also makes the previous base prunable, which is the one
 * configuration where compaction's pending {@code stack_state.base_snapshot_id} update has to reach
 * the database before the pruner's delete runs.
 */
@QuarkusTest
@TestProfile(SnapshotPrunerUpdateSpacingTest.UpdateLadderProfile.class)
class SnapshotPrunerUpdateSpacingTest extends AbstractSnapshotPruningTest {

  @Override
  String orgName() {
    return "snapshot-pruner-updates-org";
  }

  @Test
  void keepsRungsByUpdateDistanceAndNeverStrandsTheLiveBase() {
    for (int i = 1; i <= 5; i++) {
      updateAndCompact("r" + i);
      assertEquals(
          CompactionJob.Status.COMPLETED,
          latestJob().status,
          "compaction " + i + " failed: " + latestJob().errorMessage);
    }

    // Anchor at 1, then a rung every 2 updates; recency 1 keeps only the live base.
    assertEquals(List.of(1L, 3L, 5L), snapshotBoundaries());
  }

  public static final class UpdateLadderProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "statedb.compaction.prune.newest-snapshots", "1",
          "statedb.compaction.prune.ladder-journal-spacing", "0",
          "statedb.compaction.prune.ladder-update-spacing", "2");
    }
  }
}
