package io.statedb.pulumiapi.stack;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class StackResource {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> get(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    return Map.of("hello", "stack");
  }
}
