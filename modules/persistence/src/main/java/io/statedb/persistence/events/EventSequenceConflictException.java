package io.statedb.persistence.events;

import java.util.UUID;

/**
 * Thrown when an event batch carries a {@code sequence} already persisted for this update. Maps to
 * HTTP 409. The {@code UNIQUE(update_id, sequence)} constraint is the source of truth, so a
 * duplicate retry surfaces here rather than silently double-writing.
 */
public class EventSequenceConflictException extends RuntimeException {

  private final UUID updateId;

  public EventSequenceConflictException(UUID updateId, Throwable cause) {
    super("duplicate event sequence for update " + updateId, cause);
    this.updateId = updateId;
  }

  public UUID updateId() {
    return updateId;
  }
}
