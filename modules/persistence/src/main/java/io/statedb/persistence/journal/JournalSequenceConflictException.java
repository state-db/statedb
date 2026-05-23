package io.statedb.persistence.journal;

import java.util.UUID;

/**
 * Thrown when a journal PATCH carries a {@code wire_sequenceID} that is already persisted for this
 * update. Maps to HTTP 409. See {@link JournalAppender} — we rely on the {@code UNIQUE(update_id,
 * wire_sequence_id)} constraint as the source of truth, so a duplicate retry surfaces here rather
 * than silently double-writing.
 */
public class JournalSequenceConflictException extends RuntimeException {

  private final UUID updateId;

  public JournalSequenceConflictException(UUID updateId, Throwable cause) {
    super("duplicate wire sequenceID for update " + updateId, cause);
    this.updateId = updateId;
  }

  public UUID updateId() {
    return updateId;
  }
}
