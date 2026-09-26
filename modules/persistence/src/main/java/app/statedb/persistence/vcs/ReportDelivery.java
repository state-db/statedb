package app.statedb.persistence.vcs;

import app.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One optional report per run. No relationship or write to the update-history table. */
@Entity
@Table(name = "report_deliveries")
public class ReportDelivery extends TimestampedEntity {

  @Column(name = "update_id", nullable = false)
  public UUID updateId;

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Column(name = "connection_id", nullable = false)
  public UUID connectionId;

  @Column(name = "repository_owner", nullable = false)
  public String repositoryOwner;

  @Column(name = "repository_name", nullable = false)
  public String repositoryName;

  @Column(name = "head_sha", nullable = false)
  public String headSha;

  @Column(name = "pr_number")
  public Long prNumber;

  @Column(nullable = false, insertable = false, updatable = false)
  public long generation;

  @Column(nullable = false)
  public String state = "pending";

  @Column(name = "target_repository_id")
  public String targetRepositoryId;

  @Column(name = "target_pr_number")
  public Long targetPrNumber;

  /** Frozen with the verified target before any remote mutation, so retries are immutable. */
  public String body;

  @Column(nullable = false)
  public int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  public Instant nextAttemptAt = Instant.now();

  @Column(name = "settled_at")
  public Instant settledAt;

  @Column(name = "external_id")
  public String externalId;

  @Column(name = "last_error")
  public String lastError;
}
