package io.statedb.pulumiapi.auth;

import io.smallrye.common.annotation.Blocking;
import io.statedb.persistence.auth.ApiTokenFinder;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

  @Inject CurrentPrincipal currentPrincipal;

  @Override
  public void filter(ContainerRequestContext ctx) {
    String path = normalize(ctx.getUriInfo().getPath());
    if (!requiresAuth(path)) {
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

    String hash = sha256Hex(raw);
    Optional<AuthenticatedPrincipal> principal = tokenFinder.findValidByHash(hash);
    if (principal.isEmpty()) {
      ctx.abortWith(unauthorized());
      return;
    }
    currentPrincipal.set(principal.get());
  }

  // UriInfo#getPath() returns the application path without a leading slash.
  private static String normalize(String path) {
    return path.startsWith("/") ? path : "/" + path;
  }

  static boolean requiresAuth(String path) {
    if (!path.startsWith(PULUMI_API_PREFIX)) {
      return false;
    }
    return PUBLIC_PATHS.stream().noneMatch(path::startsWith);
  }

  private static Response unauthorized() {
    return Response.status(Response.Status.UNAUTHORIZED).build();
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
