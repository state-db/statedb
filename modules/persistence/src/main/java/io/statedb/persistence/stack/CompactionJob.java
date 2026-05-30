package io.statedb.persistence.stack;

import io.statedb.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "compaction_jobs")
public class CompactionJob extends BaseEntity {

  public enum Status {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    @EnumeratedValue final String dbValue;

    Status(String dbValue) {
      this.dbValue = dbValue;
    }
  }

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Status status = Status.PENDING;

  @Column(nullable = false)
  public int attempts = 0;

  @Column(name = "error_message")
  public String errorMessage;

  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "completed_at")
  public Instant completedAt;
}
