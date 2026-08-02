package app.statedb.pulumiapi.unsupported;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Stubs the package/template registry behind {@code pulumi new}, {@code pulumi project new}, and
 * {@code pulumi package info/publish/...}. state-db stores state; it does not host a registry.
 *
 * <p>The list commands hit the bare paths; publishing nests under them as {@code
 * packages/{source}/{publisher}/{name}/versions}, which the trailing {@code {sub:(/.*)?}} covers.
 */
@Path("/pulumi/api/registry")
public class UnsupportedRegistryResource {

  private static final String TEMPLATES = "registry templates";
  private static final String PACKAGES = "registry packages";

  @GET
  @Path("/templates{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response templates() {
    return UnsupportedFeature.notSupported(TEMPLATES);
  }

  @POST
  @Path("/templates{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response publishTemplate() {
    return UnsupportedFeature.notSupported(TEMPLATES);
  }

  @GET
  @Path("/packages{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response packages() {
    return UnsupportedFeature.notSupported(PACKAGES);
  }

  @POST
  @Path("/packages{sub:(/.*)?}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response publishPackage() {
    return UnsupportedFeature.notSupported(PACKAGES);
  }
}
