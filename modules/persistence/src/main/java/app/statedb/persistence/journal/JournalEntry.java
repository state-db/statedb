package app.statedb.persistence.journal;

import app.statedb.persistence.BaseEntity;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only record of one Pulumi journal entry. The {@code sequence_id} is server-assigned as
 * {@code stack_updates.start_journal_seq + wire_sequence_id} so that {@code ORDER BY sequence_id}
 * reconstructs wire order even when entries arrive at the server out of order (see {@link
 * JournalAppender}).
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry extends BaseEntity {

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Column(name = "update_id", nullable = false)
  public UUID updateId;

  @Column(name = "sequence_id", nullable = false)
  public long sequenceId;

  @Column(name = "wire_sequence_id", nullable = false)
  public long wireSequenceId;

  @Column(nullable = false)
  public short kind;

  @Column(name = "operation_id")
  public Long operationId;

  @Column(nullable = false)
  public int version = 1;

  @Column(name = "remove_old")
  public Long removeOld;

  @Column(name = "remove_new")
  public Long removeNew;

  @Column(name = "delete_old")
  public Long deleteOld;

  @Column(name = "delete_new")
  public Long deleteNew;

  @Column(name = "pending_replacement_old")
  public Long pendingReplacementOld;

  @Column(name = "pending_replacement_new")
  public Long pendingReplacementNew;

  @Column(name = "is_refresh", nullable = false)
  public boolean isRefresh = false;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  public JsonNode payload;

  @Column(name = "resource_urn")
  public String resourceUrn;

  // Read-only navigation; writers set the UUID columns directly.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stack_id", insertable = false, updatable = false)
  public Stack stack;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "update_id", insertable = false, updatable = false)
  public StackUpdate update;
}
