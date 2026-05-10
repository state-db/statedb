package io.statedb.consoleapi.auth;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/me")
public class AuthResource {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> me() {
    return Map.of("hello", "me");
  }
}
