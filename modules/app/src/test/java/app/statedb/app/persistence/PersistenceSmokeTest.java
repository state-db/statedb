package app.statedb.app.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.Organization;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link Organization} is used as a vehicle, not a subject — if this fails, something is wrong with
 * the persistence plumbing, not the identity model. Identity table contracts are covered by {@code
 * IdentityConstraintsTest}.
 */
@QuarkusTest
class PersistenceSmokeTest extends DatabaseTest {

  @Test
  @TestTransaction
  void persistenceStackRoundTripsAnEntity() {
    Organization org = new Organization();
    org.name = "smoke";
    org.displayName = "Smoke Test Org";
    org.persistAndFlush();

    assertNotNull(org.id, "UUIDv7 generator did not assign an id");
    assertNotNull(org.createdAt, "@Generated did not read DB-default created_at back");
    assertNotNull(org.updatedAt, "@Generated did not read DB-default updated_at back");

    Organization fetched = Organization.findById(org.id);
    assertEquals("smoke", fetched.name);
  }

  /**
   * {@code updated_at} is maintained by Hibernate {@code @UpdateTimestamp} on {@link
   * app.statedb.persistence.TimestampedEntity}. Catches anyone reverting the field to a
   * {@code @Generated} mapping without a corresponding DB-side mechanism to advance it.
   */
  @Test
  @TestTransaction
  void updatedAtAdvancesOnUpdate() {
    Organization org = new Organization();
    org.name = "updated-at-trigger";
    org.displayName = "Original";
    org.persistAndFlush();
    Instant insertedAt = org.updatedAt;

    org.displayName = "Changed";
    Organization.flush();

    assertNotEquals(insertedAt, org.updatedAt, "trigger did not bump updated_at on UPDATE");
    assertTrue(org.updatedAt.isAfter(insertedAt), "updated_at must advance forward in time");
  }
}
