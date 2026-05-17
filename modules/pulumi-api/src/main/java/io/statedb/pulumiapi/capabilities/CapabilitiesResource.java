package io.statedb.pulumiapi.capabilities;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/pulumi/api/capabilities")
public class CapabilitiesResource {

  // The capability list gates which checkpoint protocol the CLI uses; bumping the version here
  // without shipping the matching server-side handler will tell the CLI to use a protocol the
  // server can't service.
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public CapabilitiesDto get() {
    return new CapabilitiesDto(List.of(new Capability("delta-checkpoint-updates", 1)));
  }

  public record CapabilitiesDto(@JsonProperty("capabilities") List<Capability> capabilities) {}

  public record Capability(
      @JsonProperty("name") String name, @JsonProperty("version") int version) {}
}
