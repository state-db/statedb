package io.statedb.pulumiapi.stack;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Stubs the stack feature endpoints behind {@code pulumi stack schedule} and {@code pulumi stack
 * webhook}. state-db is a state backend, not a deployments/automation service, so these return a
 * deliberate 501 with Pulumi-style JSON ({@code {"code":501,"message":"…"}}) — loud enough that the
 * CLI surfaces a clean error rather than hanging or 404-ing. There is no {@code @Authorize}
 * (nothing is loaded); {@link io.statedb.pulumiapi.auth.TokenAuthFilter} still enforces 401
 * globally.
 */
@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class UnsupportedFeatureResource {

  @GET
  @Path("/deployments/schedules")
  @Produces(MediaType.APPLICATION_JSON)
  public Response schedules() {
    return notSupported("schedules");
  }

  @GET
  @Path("/hooks")
  @Produces(MediaType.APPLICATION_JSON)
  public Response webhooks() {
    return notSupported("webhooks");
  }

  private static Response notSupported(String feature) {
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            Map.of(
                "code", 501, "message", "stack " + feature + " are not supported by this backend"))
        .build();
  }
}
