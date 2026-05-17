package io.statedb.pulumiapi.auth;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

// The Pulumi CLI sends `Accept: application/vnd.pulumi+N` on every request. The vendor type has
// no `+json` suffix, so Jackson doesn't auto-bind a writer and JAX-RS content negotiation 406s
// against @Produces(application/json). Rewrite the header to application/json before path
// matching. The reference recorder returns application/json regardless of Accept and the CLI
// parses it happily.
@Provider
@PreMatching
@Priority(1)
public class PulumiAcceptHeaderNormalizer implements ContainerRequestFilter {

  private static final String PULUMI_VENDOR_PREFIX = "application/vnd.pulumi";

  @Override
  public void filter(ContainerRequestContext ctx) {
    String accept = ctx.getHeaderString(HttpHeaders.ACCEPT);
    if (accept != null && accept.startsWith(PULUMI_VENDOR_PREFIX)) {
      ctx.getHeaders().putSingle(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON);
    }
  }
}
