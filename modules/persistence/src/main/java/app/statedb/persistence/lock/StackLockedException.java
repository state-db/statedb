package app.statedb.persistence.lock;

import java.util.UUID;

/** Thrown when an acquire races a live lock. Mapped to 409 by the HTTP layer. */
public class StackLockedException extends RuntimeException {

  private final UUID stackId;

  public StackLockedException(UUID stackId) {
    super("stack " + stackId + " is currently locked by another update");
    this.stackId = stackId;
  }

  public UUID stackId() {
    return stackId;
  }
}
