package io.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.statedb.persistence.policy.ClasspathPolicyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Lints the static policy set for the listing invariant (design/stack-sharing.md §3): no {@code
 * forbid} may target an action inside {@code stack:ReadOnlyAccess}. List endpoints mirror the read
 * policies as a SQL predicate; a read-affecting forbid would be SQL-inexpressible and silently
 * desynchronize listing from the PDP.
 */
class PolicyForbidLintTest {

  // The stack:ReadOnlyAccess closure plus the action groups containing it. A forbid naming any of
  // these could deny a read the SQL predicate allows.
  private static final List<String> READ_AFFECTING =
      List.of(
          "Action::\"stack:Read\"",
          "Action::\"stack:DecryptSecrets\"",
          "Action::\"stack:ReadOnlyAccess\"",
          "Action::\"stack:*\"");

  @Test
  void noStaticForbidTargetsAReadAction() {
    String text = new ClasspathPolicyStore().policies();
    // Strip line comments so prose mentioning "forbid" doesn't trip the scan.
    String stripped = text.replaceAll("//[^\n]*", "");
    Matcher m = Pattern.compile("forbid\\s*\\([^;]*;").matcher(stripped);
    List<String> forbids = new ArrayList<>();
    while (m.find()) {
      forbids.add(m.group());
    }
    assertFalse(forbids.isEmpty(), "expected at least the delete guardrail forbid");
    for (String forbid : forbids) {
      assertTrue(
          forbid.contains("action == Action::\""),
          "every forbid must pin a single leaf action — an unconstrained `action` would cover"
              + " reads: "
              + forbid);
      for (String read : READ_AFFECTING) {
        assertFalse(forbid.contains(read), "forbid targets a read action: " + forbid);
      }
    }
  }
}
