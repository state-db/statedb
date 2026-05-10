package io.statedb.consoleapi.workspace;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/workspaces")
public class WorkspacesResource {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> list() {
    return Map.of("hello", "workspaces");
  }
}
