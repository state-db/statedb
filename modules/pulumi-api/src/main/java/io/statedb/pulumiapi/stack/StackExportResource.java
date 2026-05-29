package io.statedb.pulumiapi.stack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackNotFoundException;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}/export")
public class StackExportResource {

  @Inject StackRefResolver resolver;

  @Inject StackAssembler assembler;

  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public ExportDto export(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackRef ref =
        resolver
            .resolve(org, project, stack)
            .filter(r -> r.orgId().equals(currentPrincipal.get().orgId()))
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));
    AssembledDeployment assembled = assembler.assemble(ref.stackId());
    return new ExportDto(assembled.deployment(), assembled.version(), assembled.features());
  }

  public record ExportDto(
      @JsonProperty("deployment") JsonNode deployment,
      @JsonProperty("version") int version,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("features")
          List<String> features) {}
}
