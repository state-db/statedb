package app.statedb.persistence.stack;

import app.statedb.persistence.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "snapshots")
public class Snapshot extends BaseEntity {

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Column(nullable = false)
  public int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  public JsonNode checkpoint;

  @Column(name = "compacted_from_journal_seq", nullable = false)
  public long compactedFromJournalSeq;

  // Set by every snapshot writer from checkpoint.resources.length; lets list
  // screens report counts without loading the checkpoint JSONB.
  @Column(name = "resource_count", nullable = false)
  public int resourceCount;
}
