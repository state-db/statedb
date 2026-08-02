package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.AdminAllowList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Admin matching is exact-string on the canonical {@code <iss>:<sub>} identity. */
class AdminAllowListTest {

  @Test
  void matchesConfiguredSubjectsExactly() {
    AdminAllowList allow =
        new AdminAllowList(
            Optional.of("https://accounts.google.com:115, https://accounts.google.com:117"));

    assertTrue(allow.isAdmin("https://accounts.google.com", "115"));
    assertTrue(allow.isAdmin("https://accounts.google.com", "117"));
    assertFalse(allow.isAdmin("https://accounts.google.com", "999"));
    // Same subject, different issuer must not match.
    assertFalse(allow.isAdmin("https://evil.example.com", "115"));
  }

  @Test
  void emptyOrAbsentMeansNoAdmins() {
    assertFalse(new AdminAllowList(Optional.empty()).isAdmin("https://idp", "s"));
    assertFalse(new AdminAllowList(Optional.of("   ")).isAdmin("https://idp", "s"));
  }
}
