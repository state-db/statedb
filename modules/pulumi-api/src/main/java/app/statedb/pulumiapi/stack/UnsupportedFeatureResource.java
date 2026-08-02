package app.statedb.pulumiapi.stack;

import app.statedb.pulumiapi.unsupported.UnsupportedFeature;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Stubs the stack feature endpoints behind {@code pulumi stack schedule}, {@code pulumi stack
 * webhook}, {@code pulumi stack drift}, {@code pulumi deployment list}, and {@code pulumi
 * deployment settings}. state-db is a state backend, not a deployments/automation service, so these
 * answer with a deliberate "not supported" error rather than hanging or 404-ing with an empty body
 * — see {@link UnsupportedFeature} for the response shape and why it is not a 501. There is no
 * {@code @Authorize} (nothing is loaded); {@link app.statedb.pulumiapi.auth.TokenAuthFilter} still
 * enforces 401 globally.
 */
@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class UnsupportedFeatureResource {

  private static final String SCHEDULES = "stack schedules";
  private static final String WEBHOOKS = "stack webhooks";
  private static final String DEPLOYMENTS = "stack deployments";
  private static final String DEPLOYMENT_SETTINGS = "stack deployment settings";
  private static final String DRIFT = "stack drift detection runs";

  // The CLI has three kinds of schedule, distinguished by an infix in the path: plain
  // (deployments/schedules), drift (deployments/drift/schedules), and TTL
  // (deployments/ttl/schedules). One template covers all three plus their /{scheduleID} forms.
  private static final String SCHEDULES_PATH =
      "/deployments{kind:(/drift|/ttl)?}/schedules{sub:(/.*)?}";

  @GET
  @Path(SCHEDULES_PATH)
  @Produces(MediaType.APPLICATION_JSON)
  public Response schedules() {
    return UnsupportedFeature.notSupported(SCHEDULES);
  }

  @POST
  @Path(SCHEDULES_PATH)
  @Produces(MediaType.APPLICATION_JSON)
  public Response createSchedule() {
    return UnsupportedFeature.notSupported(SCHEDULES);
  }

  @DELETE
  @Path(SCHEDULES_PATH)
  @Produces(MediaType.APPLICATION_JSON)
  public Response deleteSchedule() {
    return UnsupportedFeature.notSupported(SCHEDULES);
  }

  // `stack drift status` hits drift/status, `stack drift list` hits drift/runs.
  @GET
  @Path("/drift{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response drift() {
    return UnsupportedFeature.notSupported(DRIFT);
  }

  @GET
  @Path("/hooks{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response webhooks() {
    return UnsupportedFeature.notSupported(WEBHOOKS);
  }

  @POST
  @Path("/hooks{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response createWebhook() {
    return UnsupportedFeature.notSupported(WEBHOOKS);
  }

  @GET
  @Path("/deployments")
  @Produces(MediaType.APPLICATION_JSON)
  public Response deployments() {
    return UnsupportedFeature.notSupported(DEPLOYMENTS);
  }

  @POST
  @Path("/deployments")
  @Produces(MediaType.APPLICATION_JSON)
  public Response runDeployment() {
    return UnsupportedFeature.notSupported(DEPLOYMENTS);
  }

  @GET
  @Path("/deployments/settings{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getDeploymentSettings() {
    return UnsupportedFeature.notSupported(DEPLOYMENT_SETTINGS);
  }

  @POST
  @Path("/deployments/settings{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response encryptDeploymentSecret() {
    return UnsupportedFeature.notSupported(DEPLOYMENT_SETTINGS);
  }

  @PUT
  @Path("/deployments/settings{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateDeploymentSettings() {
    return UnsupportedFeature.notSupported(DEPLOYMENT_SETTINGS);
  }

  @DELETE
  @Path("/deployments/settings{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response destroyDeploymentSettings() {
    return UnsupportedFeature.notSupported(DEPLOYMENT_SETTINGS);
  }
}
