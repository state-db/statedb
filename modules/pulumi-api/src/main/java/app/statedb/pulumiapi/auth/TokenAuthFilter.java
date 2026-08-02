package app.statedb.pulumiapi.auth;

import app.statedb.persistence.auth.ApiTokenFinder;
import app.statedb.persistence.auth.ApiTokenHasher;
import app.statedb.persistence.auth.ApiTokenUsageRecorder;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.CurrentPrincipal;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.Optional;

// Not @PreMatching: Quarkus REST does not dispatch @PreMatching filters to worker threads, but
// the token lookup is a blocking JDBC call. A global @Blocking filter is functionally
// equivalent here — every gated route is a mapped resource, and unmapped paths 404 before
// reaching the filter, which is the correct default.
@Provider
@Priority(Priorities.AUTHENTICATION)
@Blocking
public class TokenAuthFilter implements ContainerRequestFilter {

  private static final String SCHEME = "token ";

  private static final String PULUMI_API_PREFIX = "/pulumi/api/";

  // Routes under /pulumi/api/ that bypass authentication. Default-deny: everything else under
  // the prefix requires a valid token. Adding an entry here is a security change — only do it
  // for routes that must be reachable without credentials (e.g. health checks).
  private static final List<String> PUBLIC_PATHS = List.of();

  @Inject ApiTokenFinder tokenFinder;

  @Inject ApiTokenHasher tokenHasher;

  @Inject ApiTokenUsageRecorder usageRecorder;

  @Inject CurrentPrincipal currentPrincipal;

  @Override
  public void filter(ContainerRequestContext ctx) {
    String path = RequestPaths.normalizedPath(ctx);
    if (!requiresAuth(ctx.getMethod(), path)) {
      return;
    }

    String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(SCHEME)) {
      ctx.abortWith(unauthorized());
      return;
    }
    String raw = header.substring(SCHEME.length()).trim();
    if (raw.isEmpty()) {
      ctx.abortWith(unauthorized());
      return;
    }

    String hash = tokenHasher.hash(raw);
    Optional<AuthenticatedPrincipal> principal = tokenFinder.findValidByHash(hash);
    if (principal.isEmpty()) {
      ctx.abortWith(unauthorized());
      return;
    }
    currentPrincipal.set(principal.get());
    usageRecorder.touch(principal.get().apiTokenId());
  }

  static boolean requiresAuth(String method, String path) {
    if (!path.startsWith(PULUMI_API_PREFIX)) {
      return false;
    }
    if (LeaseRouteMatcher.isLeaseScoped(method, path)) {
      // Lease-scoped routes authenticate via Authorization: update-token in LeaseTokenFilter.
      return false;
    }
    return PUBLIC_PATHS.stream().noneMatch(path::startsWith);
  }

  private static Response unauthorized() {
    return Response.status(Response.Status.UNAUTHORIZED).build();
  }
}
