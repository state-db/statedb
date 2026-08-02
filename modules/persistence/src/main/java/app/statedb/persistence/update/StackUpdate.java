package app.statedb.persistence.update;

import app.statedb.persistence.TimestampedEntity;
import app.statedb.persistence.stack.Stack;
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

    /** The Pulumi wire value (e.g. {@code "update"}), as emitted in {@code stack history}. */
    public String wireValue() {
      return dbValue;
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

    public String wireValue() {
      return dbValue;
    }
  }

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  /**
   * Applied-update sequence number, 1-based per stack ({@code pulumi stack export --version N}
   * addressing). Dry-run rows do not consume the sequence and carry 0.
   */
  @Column(nullable = false)
  public int version;

  /**
   * True for the dry-run pass of up/refresh/destroy ({@code options.dryRun}) and for {@code pulumi
   * preview}. Dry-run rows never advance stack versions and stay out of {@code stack history}.
   */
  @Column(name = "dry_run", nullable = false)
  public boolean dryRun;

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

  /**
   * Snapshot version installed by this update, set only by snapshot-installing operations (import,
   * rename). Those snapshots start a new lineage — their content is not the fold of the journal
   * prefix at their boundary — so historical assembly treats the earliest stamp after a target
   * version as a fence and never uses that snapshot version or higher as a base. Null for
   * journal-driven updates.
   */
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

  /**
   * When {@link app.statedb.persistence.events.EventArchiveFolder} settled this update's events —
   * either by folding them into an archive or by finding nothing foldable. Null means the folder
   * has not considered it yet, and is what its candidate sweep indexes on, so the sweep costs what
   * is outstanding rather than what has ever run.
   */
  @Column(name = "events_folded_at")
  public Instant eventsFoldedAt;

  @Column(name = "started_by_user_id")
  public UUID startedByUserId;

  @Column(name = "started_by_api_token_id")
  public UUID startedByApiTokenId;

  // Read-only navigation; writers set stackId directly.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stack_id", insertable = false, updatable = false)
  public Stack stack;
}
