package io.statedb.pulumiapi.user;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/pulumi/api/user")
public class UserResource {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> get() {
    return Map.of("hello", "user");
  }
}
