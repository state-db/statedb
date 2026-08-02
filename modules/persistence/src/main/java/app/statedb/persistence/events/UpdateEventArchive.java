package app.statedb.persistence.events;

import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A terminal update's whole event stream, serialized and compressed into one row. Written by {@link
 * EventArchiveFolder} once the update has been terminal for the configured grace period; its
 * presence is what tells readers to serve that update's events from the blob rather than from
 * {@code update_events}.
 *
 * <p>{@code updateId} is the primary key rather than a generated one: it is the whole of the
 * idempotency guarantee, so two pods folding the same update cannot produce two archives.
 *
 * <p>{@code eventCount} and {@code maxSequence} are redundant with the blob and exist to be checked
 * against the rows before they are deleted.
 */
@Entity
@Table(name = "update_event_archives")
public class UpdateEventArchive extends PanacheEntityBase {

  @Id
  @Column(name = "update_id")
  public UUID updateId;

  @Column(name = "stack_id", nullable = false)
  public UUID stackId;

  /** Compression codec the blob was written with; see {@link EventArchiveCodec}. */
  @Column(nullable = false)
  public String codec;

  @Column(name = "event_count", nullable = false)
  public int eventCount;

  @Column(name = "max_sequence", nullable = false)
  public int maxSequence;

  @Column(nullable = false)
  public byte[] events;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  // Read-only navigation; writers set the UUID columns directly.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "stack_id", insertable = false, updatable = false)
  public Stack stack;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "update_id", insertable = false, updatable = false)
  public StackUpdate update;
}
