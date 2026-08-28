package app.statedb.persistence;

import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/** Application-generated identifiers for persistence writes that bypass entity lifecycle hooks. */
public final class PersistenceIds {

  private PersistenceIds() {}

  /** Generates the same time-ordered UUIDv7 used by {@link BaseEntity}. */
  public static UUID newId() {
    return UuidVersion7Strategy.INSTANCE.generateUuid(null);
  }
}
