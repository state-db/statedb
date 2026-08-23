package app.statedb.pulumiapi.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.pulumiapi.update.LeaseResource;
import app.statedb.pulumiapi.update.UpdateResource;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Default-deny: every path under /pulumi/api/ requires auth unless explicitly exempted.
// HTTP-level coverage in TokenAuthFilterTest only sees mapped routes; this file pins the
// predicate against paths that may never have a handler so the invariant survives unmapped
// callers and future routes alike.
class TokenAuthFilterPredicateTest {

  private static final Set<Route> UPDATE_RESOURCE_ROUTES =
      Set.of(
          new Route("POST", "/update/{updateID}"),
          new Route("GET", "/update/{updateID}"),
          new Route("POST", "/update/{updateID}/cancel"));

  private static final Set<Route> LEASE_RESOURCE_ROUTES =
      Set.of(
          new Route("POST", "/update/{updateID}/renew_lease"),
          new Route("PATCH", "/update/{updateID}/journalentries"),
          new Route("POST", "/update/{updateID}/events/batch"),
          new Route("PATCH", "/update/{updateID}/checkpoint"),
          new Route("PATCH", "/update/{updateID}/checkpointdelta"),
          new Route("PATCH", "/update/{updateID}/checkpointverbatim"),
          new Route("POST", "/update/{updateID}/complete"),
          new Route("DELETE", "/update/{updateID}"));

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
    // Mutating update data paths are lease-scoped.
    assertFalse(TokenAuthFilter.requiresAuth("POST", basePath + "/renew_lease"));
    assertFalse(TokenAuthFilter.requiresAuth("PATCH", basePath + "/checkpoint"));
    assertFalse(TokenAuthFilter.requiresAuth("POST", basePath + "/events/batch"));
    assertFalse(TokenAuthFilter.requiresAuth("POST", basePath + "/complete"));

    // Forced cancel mirrors Pulumi's POST /cancel endpoint and uses the regular API token, since
    // the caller is often a second CLI that does not hold the update lease.
    assertTrue(TokenAuthFilter.requiresAuth("POST", basePath + "/cancel"));
  }

  @Test
  void updateRoutesStayPartitionedByAuthenticationScheme() {
    assertEquals(UPDATE_RESOURCE_ROUTES, resourceRoutes(UpdateResource.class));
    assertEquals(LEASE_RESOURCE_ROUTES, resourceRoutes(LeaseResource.class));

    String root = "/pulumi/api/stacks/o/p/s";
    String updateId = "00000000-0000-0000-0000-000000000000";
    for (Route route : UPDATE_RESOURCE_ROUTES) {
      assertTrue(
          TokenAuthFilter.requiresAuth(
              route.method(), root + route.path().replace("{updateID}", updateId)));
    }
    for (Route route : LEASE_RESOURCE_ROUTES) {
      assertFalse(
          TokenAuthFilter.requiresAuth(
              route.method(), root + route.path().replace("{updateID}", updateId)));
    }
  }

  @Test
  void onlyRegisteredLeaseMethodsBypassTokenAuth() {
    String basePath = "/pulumi/api/stacks/o/p/s/update/00000000-0000-0000-0000-000000000000";

    assertTrue(TokenAuthFilter.requiresAuth("GET", basePath + "/renew_lease"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", basePath + "/events/batch"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", basePath + "/complete"));
    assertTrue(TokenAuthFilter.requiresAuth("GET", basePath + "/checkpoint"));
  }

  private static Set<Route> resourceRoutes(Class<?> resourceClass) {
    Set<Route> routes = new HashSet<>();
    for (Method method : resourceClass.getDeclaredMethods()) {
      Path path = method.getAnnotation(Path.class);
      if (path == null || !path.value().contains("/update/{updateID}")) {
        continue;
      }
      for (String verb : httpMethods(method)) {
        routes.add(new Route(verb, path.value()));
      }
    }
    return routes;
  }

  private static Set<String> httpMethods(Method method) {
    Set<String> methods = new HashSet<>();
    if (method.isAnnotationPresent(POST.class)) {
      methods.add("POST");
    }
    if (method.isAnnotationPresent(GET.class)) {
      methods.add("GET");
    }
    if (method.isAnnotationPresent(PATCH.class)) {
      methods.add("PATCH");
    }
    if (method.isAnnotationPresent(DELETE.class)) {
      methods.add("DELETE");
    }
    return methods;
  }

  private record Route(String method, String path) {}
}
