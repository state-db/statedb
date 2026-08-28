package app.statedb.pulumiapi.auth;

import app.statedb.persistence.lock.LeaseContext;
import app.statedb.persistence.lock.LeaseLookup;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRefResolver;
import app.statedb.pulumiapi.error.PulumiErrorResponses;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates lease-scoped update-data routes. Header shape is {@code Authorization: update-token
 * <UUID>}. Splits failure modes:
 *
 * <ul>
 *   <li>Missing / malformed / wrong-scheme header → 401 (you didn't authenticate)
 *   <li>Token unknown / expired / path-stack mismatch / path-updateId mismatch → 409 (your lease is
 *       no longer valid; matches Pulumi's "stack is locked" semantics).
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 1)
@Blocking
public class LeaseTokenFilter implements ContainerRequestFilter {

  private static final String SCHEME = "update-token ";

  @Inject LeaseLookup leaseLookup;

  @Inject StackRefResolver stackRefResolver;

  @Inject LeaseContextHolder holder;

  @Override
  public void filter(ContainerRequestContext ctx) {
    String path = RequestPaths.normalizedPath(ctx);
    if (!LeaseRouteMatcher.isLeaseScoped(ctx.getMethod(), path)) {
      return;
    }

    String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(SCHEME)) {
      ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
      return;
    }
    String raw = header.substring(SCHEME.length()).trim();
    UUID token;
    try {
      token = UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
      return;
    }

    Optional<LeaseContext> lease = leaseLookup.findActive(token);
    if (lease.isEmpty()) {
      ctx.abortWith(conflict());
      return;
    }

    // isLeaseScoped matched above, so the same pattern parses.
    LeaseRouteMatcher.UpdatePath parts = LeaseRouteMatcher.parse(path).orElseThrow();

    UUID pathUpdateId;
    try {
      pathUpdateId = UUID.fromString(parts.updateId());
    } catch (IllegalArgumentException e) {
      ctx.abortWith(conflict());
      return;
    }

    Optional<StackRef> ref = stackRefResolver.resolve(parts.org(), parts.project(), parts.stack());
    if (ref.isEmpty() || !ref.get().stackId().equals(lease.get().stackId())) {
      ctx.abortWith(conflict());
      return;
    }
    if (!pathUpdateId.equals(lease.get().updateId())) {
      ctx.abortWith(conflict());
      return;
    }

    holder.set(lease.get());
  }

  private static Response conflict() {
    return PulumiErrorResponses.conflict("update lease is no longer valid");
  }
}
