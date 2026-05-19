package io.statedb.persistence.update;

import io.statedb.persistence.TimestampedEntity;
import io.statedb.persistence.stack.Stack;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stack_updates")
public class StackUpdate extends TimestampedEntity {

  public enum Kind {
    UPDATE("update"),
    PREVIEW("preview"),
    DESTROY("destroy"),
    REFRESH("refresh"),
    IMPORT("import"),
    RENAME("rename");

    @EnumeratedValue final String dbValue;

    Kind(String dbValue) {
      this.dbValue = dbValue;
    }
  }

  public enum Status {
    IN_PROGRESS("in-progress"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    @EnumeratedValue final String dbValue;

    Status(String dbValue) {
      this.dbValue = dbValue;
    }
  }

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Column(nullable = false)
  public int version;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Kind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Status status;

  @Column(name = "client_request_id")
  public String clientRequestId;

  @Column(name = "journal_version")
  public Integer journalVersion;

  @Column(name = "base_snapshot_version")
  public Integer baseSnapshotVersion;

  @Column(name = "start_journal_seq")
  public Long startJournalSeq;

  @Column(name = "end_journal_seq")
  public Long endJournalSeq;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public Map<String, Object> config;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public Map<String, Object> metadata;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public Map<String, Object> options;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public Map<String, Object> tags;

  @Column public String description;

  @Column public String runtime;

  @Column(name = "pulumi_version")
  public String pulumiVersion;

  @Column public String message;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  @Column(name = "ended_at")
  public Instant endedAt;

  // Read-only navigation; writers set stackId directly.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stack_id", insertable = false, updatable = false)
  public Stack stack;
}
