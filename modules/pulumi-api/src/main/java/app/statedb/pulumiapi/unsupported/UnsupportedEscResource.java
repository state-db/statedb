package app.statedb.pulumiapi.unsupported;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Stubs the ESC environment surface behind {@code pulumi env *} and {@code pulumi config env *}.
 * ESC is a secrets/configuration service, not state storage, and is out of scope for statedb.
 *
 * <p>{@code env ls} hits the bare path; everything else nests under it as {@code
 * environments/{org}/{name}}, which the trailing {@code {sub:(/.*)?}} covers.
 */
@Path("/pulumi/api/esc")
public class UnsupportedEscResource {

  private static final String ENVIRONMENTS = "ESC environments";

  @GET
  @Path("/environments{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response environments() {
    return UnsupportedFeature.notSupported(ENVIRONMENTS);
  }

  @POST
  @Path("/environments{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response createEnvironment() {
    return UnsupportedFeature.notSupported(ENVIRONMENTS);
  }
}
