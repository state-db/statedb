package io.statedb.app.web;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Client-side routing fallback for the embedded web console.
 *
 * <p>The SPA owns paths that have no server-side resource (e.g. {@code /stacks/foo}). A browser
 * deep-link or refresh on such a path must return {@code index.html} so the TanStack router can
 * take over. This registers a last-resort GET handler that reroutes those requests to {@code
 * /index.html}; it runs only after Quarkus's static-resource handler has missed.
 *
 * <p>It deliberately does NOT match {@code /api/*}, {@code /pulumi/*}, or {@code /q/*} so unknown
 * API paths keep their normal JSON 404 (returning an HTML page there would confuse the Pulumi CLI),
 * and it skips paths whose last segment looks like a file (has a dot) so a missing asset still 404s
 * instead of yielding the app shell.
 */
@ApplicationScoped
public class SpaRouteFilter {

  void init(@Observes Router router) {
    // Integer.MAX_VALUE → runs after the static-resource handler, which calls next() on a miss.
    router.route().order(Integer.MAX_VALUE).handler(this::fallbackToIndex);
  }

  private void fallbackToIndex(RoutingContext rc) {
    if (rc.request().method() != HttpMethod.GET) {
      rc.next();
      return;
    }

    String path = rc.normalizedPath();
    if (path.startsWith("/api") || path.startsWith("/pulumi") || path.startsWith("/q/")) {
      rc.next();
      return;
    }

    String lastSegment = path.substring(path.lastIndexOf('/') + 1);
    if (lastSegment.indexOf('.') >= 0) {
      rc.next();
      return;
    }

    rc.reroute("/index.html");
  }
}
