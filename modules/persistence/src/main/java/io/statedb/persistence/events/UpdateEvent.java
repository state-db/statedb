package io.statedb.persistence.events;

import com.fasterxml.jackson.databind.JsonNode;
import io.statedb.persistence.BaseEntity;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.update.StackUpdate;
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
 * Append-only decoded Pulumi engine event from {@code POST .../events/batch}. The wire {@code
 * sequence} is preserved verbatim and unique per update; {@code kind} is the JSON envelope key with
 * the trailing {@code "Event"} stripped (e.g. {@code "resourcePreEvent"} → {@code "resourcePre"}).
 */
@Entity
@Table(name = "update_events")
public class UpdateEvent extends BaseEntity {

  /**
   * Kind of the run-summary event. Run detail and the console history list read it on every view,
   * so {@link EventArchiveFolder} leaves these rows behind when it folds the rest of the stream.
   */
  public static final String SUMMARY_KIND = "summary";

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  @Column(name = "update_id", nullable = false)
  public UUID updateId;

  @Column(nullable = false)
  public int sequence;

  @Column(nullable = false)
  public String kind;

  @Column public String severity;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  public JsonNode payload;

  // Read-only navigation; writers set the UUID columns directly.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stack_id", insertable = false, updatable = false)
  public Stack stack;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "update_id", insertable = false, updatable = false)
  public StackUpdate update;
}
