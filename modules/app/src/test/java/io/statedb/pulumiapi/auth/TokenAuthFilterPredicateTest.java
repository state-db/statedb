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
    assertTrue(TokenAuthFilter.requiresAuth("GET", "/pulumi/api/user"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", "/pulumi/api/capabilities"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", "/pulumi/api/stacks/foo/bar/baz"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", "/pulumi/api/some-future-route"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", "/pulumi/api/deeply/nested/anything"));
    // POST acquire on the bare update path stays on token auth.
    assertTrue(
        TokenAuthFilter.requiresAuth(
            "POST", "/pulumi/api/stacks/o/p/s/update/00000000-0000-0000-0000-000000000000"));
  }

  @Test
  void nonPulumiApiPathsAreNotGated() {
    assertFalse(TokenAuthFilter.requiresAuth("GET", "/q/health"));
    assertFalse(TokenAuthFilter.requiresAuth("GET", "/api/user"));
    assertFalse(TokenAuthFilter.requiresAuth("GET", "/"));
    assertFalse(TokenAuthFilter.requiresAuth("GET", "/pulumi"));
    // Trailing slash on the prefix means /pulumi/api alone is not in scope; only paths
    // explicitly under the API root are.
    assertFalse(TokenAuthFilter.requiresAuth("GET", "/pulumi/api"));
  }

  @Test
  void leaseScopedRoutesBypassTokenAuth() {
    String basePath = "/pulumi/api/stacks/o/p/s/update/00000000-0000-0000-0000-000000000000";
    // Cancel (DELETE on the bare path) is lease-scoped.
    assertFalse(TokenAuthFilter.requiresAuth("DELETE", basePath));
    // Deeper sub-paths (future renew/checkpoint/events/complete) are lease-scoped.
    assertFalse(TokenAuthFilter.requiresAuth("POST", basePath + "/renew_lease"));
    assertFalse(TokenAuthFilter.requiresAuth("PATCH", basePath + "/checkpoint"));
    assertFalse(TokenAuthFilter.requiresAuth("POST", basePath + "/events/batch"));
    assertFalse(TokenAuthFilter.requiresAuth("POST", basePath + "/complete"));
  }
}
