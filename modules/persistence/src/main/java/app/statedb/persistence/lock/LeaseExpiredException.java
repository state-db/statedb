package app.statedb.persistence.lock;

import java.util.UUID;

/**
 * Thrown when an admitted lease has expired or been deleted before its mutating transaction runs.
 * Mapped to 409, matching {@link StackLockedException}'s wire semantics.
 */
public class LeaseExpiredException extends RuntimeException {

  private final UUID leaseId;

  public LeaseExpiredException(UUID leaseId) {
    super("lease " + leaseId + " is no longer live");
    this.leaseId = leaseId;
  }

  public UUID leaseId() {
    return leaseId;
  }
}
