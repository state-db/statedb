package io.statedb.persistence.stack;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "stack_state")
public class StackState extends TimestampedEntity {

  @Column(name = "stack_id", nullable = false, unique = true)
  public UUID stackId;

  @Column(name = "base_snapshot_id")
  public UUID baseSnapshotId;

  @Column(name = "base_snapshot_version", nullable = false)
  public int baseSnapshotVersion = 0;

  @Column(name = "journal_seq", nullable = false)
  public long journalSeq = 0L;

  @Column(name = "journal_entries_since_compaction", nullable = false)
  public int journalEntriesSinceCompaction = 0;

  @Column(name = "auto_compact_threshold", nullable = false)
  public int autoCompactThreshold = 100;
}
