package io.statedb.pulumiapi.auth;

import io.smallrye.common.annotation.Blocking;
import io.statedb.persistence.lock.LeaseContext;
import io.statedb.persistence.lock.LeaseLookup;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates lease-scoped routes (cancel + everything under {@code .../update/{updateID}/*}).
 * Header shape is {@code Authorization: update-token <UUID>}. Splits failure modes:
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

  private static final Pattern PATH_PARTS =
      Pattern.compile("^/pulumi/api/stacks/([^/]+)/([^/]+)/([^/]+)/update/([^/]+)(?:/.*)?$");

  @Inject LeaseLookup leaseLookup;

  @Inject StackRefResolver stackRefResolver;

  @Inject LeaseContextHolder holder;

  @Override
  public void filter(ContainerRequestContext ctx) {
    String path = normalize(ctx.getUriInfo().getPath());
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

    Matcher m = PATH_PARTS.matcher(path);
    if (!m.matches()) {
      ctx.abortWith(conflict());
      return;
    }
    String org = m.group(1);
    String project = m.group(2);
    String stack = m.group(3);
    String rawUpdateId = m.group(4);

    UUID pathUpdateId;
    try {
      pathUpdateId = UUID.fromString(rawUpdateId);
    } catch (IllegalArgumentException e) {
      ctx.abortWith(conflict());
      return;
    }

    Optional<StackRef> ref = stackRefResolver.resolve(org, project, stack);
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
    return Response.status(Response.Status.CONFLICT).build();
  }

  private static String normalize(String path) {
    return path.startsWith("/") ? path : "/" + path;
  }
}
