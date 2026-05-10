package io.statedb.consoleapi.token;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/tokens")
public class TokensResource {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> list() {
    return Map.of("hello", "tokens");
  }
}
