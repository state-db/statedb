package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.identity.UserUpserter;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * First login creates the user; later logins refresh the same row in place (atomic, no duplicate).
 */
@QuarkusTest
class UserUpserterTest extends DatabaseTest {

  @Inject UserUpserter upserter;

  private static final String ISS = "https://idp.test";
  private static final String SUB = "user-abc";

  @Test
  @TestTransaction
  void createsThenRefreshesSameRow() {
    // Two upserts, then a single read — never load the entity between native writes, otherwise the
    // first-level cache would mask the refreshed values.
    upserter.upsertOnLogin(ISS, SUB, "first@test.example", "First Name");
    upserter.upsertOnLogin(ISS, SUB, "second@test.example", "Second Name");

    assertEquals(1, User.count("iss = ?1 and sub = ?2", ISS, SUB), "no duplicate user rows");

    User reread = User.find("iss = ?1 and sub = ?2", ISS, SUB).firstResult();
    assertEquals(7, reread.id.version(), "native insert preserves the UUIDv7 PK invariant");
    assertEquals("second@test.example", reread.email, "profile refreshed on re-login");
    assertEquals("Second Name", reread.displayName);
    assertNotNull(reread.lastLoginAt, "last_login_at set");
  }
}
