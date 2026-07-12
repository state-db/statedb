package io.statedb.pulumiapi.auth;

import jakarta.ws.rs.container.ContainerRequestContext;

/** Path helpers shared by the auth filters. */
final class RequestPaths {

  private RequestPaths() {}

  /**
   * The request path with a guaranteed leading slash — {@code UriInfo#getPath()} returns the
   * application path without one.
   */
  static String normalizedPath(ContainerRequestContext ctx) {
    String path = ctx.getUriInfo().getPath();
    return path.startsWith("/") ? path : "/" + path;
  }
}
