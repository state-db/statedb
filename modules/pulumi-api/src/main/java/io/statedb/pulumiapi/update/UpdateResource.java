package io.statedb.pulumiapi.update;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}/update")
public class UpdateResource {

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> create(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    return Map.of("hello", "update");
  }
}
