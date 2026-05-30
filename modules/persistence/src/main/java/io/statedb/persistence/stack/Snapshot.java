package io.statedb.persistence.stack;

import com.fasterxml.jackson.databind.JsonNode;
import io.statedb.persistence.BaseEntity;
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
}
