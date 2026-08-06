package app.statedb.app.ops;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Reports which build is running. */
@Path("/api/version")
public class VersionResource {

  private static final BuildInfo BUILD_INFO = BuildInfo.fromClasspath();

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public BuildInfo version() {
    return BUILD_INFO;
  }
}
