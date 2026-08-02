package app.statedb.persistence.journal;

import java.util.UUID;

/**
 * Thrown when a journal PATCH carries a {@code wire_sequenceID} that is already persisted for this
 * update with different contents. Maps to HTTP 409. Exact duplicate retries are accepted as
 * idempotent no-ops; conflicts mean the same wire replay position is being reused for different
 * state.
 */
public class JournalSequenceConflictException extends RuntimeException {

  private final UUID updateId;

  public JournalSequenceConflictException(UUID updateId, Throwable cause) {
    super("duplicate wire sequenceID for update " + updateId, cause);
    this.updateId = updateId;
  }

  public JournalSequenceConflictException(UUID updateId) {
    super("duplicate wire sequenceID with different contents for update " + updateId);
    this.updateId = updateId;
  }

  public UUID updateId() {
    return updateId;
  }
}
