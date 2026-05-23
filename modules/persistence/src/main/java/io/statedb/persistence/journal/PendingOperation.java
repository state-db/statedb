package io.statedb.persistence.journal;

import io.statedb.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Resource operations that have begun (kind=0) but not yet terminated (kind=1/2/3). Used by crash
 * recovery to surface operations that were in flight when an update died. Stale rows for terminated
 * updates are swept by compaction (later slice).
 */
@Entity
@Table(name = "pending_operations")
public class PendingOperation extends BaseEntity {

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Column(name = "update_id", nullable = false)
  public UUID updateId;

  @Column(name = "operation_id", nullable = false)
  public long operationId;

  @Column(name = "resource_urn", nullable = false)
  public String resourceUrn;

  @Column(name = "resource_type", nullable = false)
  public String resourceType;

  @Column(name = "operation_type", nullable = false)
  public String operationType;

  @Column(name = "begin_journal_seq", nullable = false)
  public long beginJournalSeq;
}
