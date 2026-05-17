package io.statedb.pulumiapi.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// Default-deny: every path under /pulumi/api/ requires auth unless explicitly exempted.
// HTTP-level coverage in TokenAuthFilterTest only sees mapped routes; this file pins the
// predicate against paths that may never have a handler so the invariant survives unmapped
// callers and future routes alike.
class TokenAuthFilterPredicateTest {

  @Test
  void everyPulumiApiPathIsGatedByDefault() {
    assertTrue(TokenAuthFilter.requiresAuth("/pulumi/api/user"));
    assertTrue(TokenAuthFilter.requiresAuth("/pulumi/api/capabilities"));
    assertTrue(TokenAuthFilter.requiresAuth("/pulumi/api/stacks/foo/bar/baz"));
    assertTrue(TokenAuthFilter.requiresAuth("/pulumi/api/some-future-route"));
    assertTrue(TokenAuthFilter.requiresAuth("/pulumi/api/deeply/nested/anything"));
  }

  @Test
  void nonPulumiApiPathsAreNotGated() {
    assertFalse(TokenAuthFilter.requiresAuth("/q/health"));
    assertFalse(TokenAuthFilter.requiresAuth("/api/me"));
    assertFalse(TokenAuthFilter.requiresAuth("/"));
    assertFalse(TokenAuthFilter.requiresAuth("/pulumi"));
    // Trailing slash on the prefix means /pulumi/api alone is not in scope; only paths
    // explicitly under the API root are.
    assertFalse(TokenAuthFilter.requiresAuth("/pulumi/api"));
  }
}
