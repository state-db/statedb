package io.statedb.pulumiapi.stack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.stack.StackDeleter;
import io.statedb.persistence.stack.StackHead;
import io.statedb.persistence.stack.StackHeadFinder;
import io.statedb.persistence.stack.StackNotFoundException;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class StackResource {

  @Inject StackHeadFinder finder;

  @Inject StackRefResolver resolver;

  @Inject StackDeleter deleter;

  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public StackHeadDto get(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackHead head =
        finder
            .find(org, project, stack)
            // Tenant guard: cross-org reads collapse to 404 (per db-schema.md), never 403 —
            // a 403 would confirm the target stack exists in another org.
            .filter(h -> h.orgId().equals(currentPrincipal.get().orgId()))
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));
    return new StackHeadDto(
        head.activeUpdate(), head.orgName(), head.projectName(), head.stackName(), head.tags());
  }

  @DELETE
  @Produces(MediaType.APPLICATION_JSON)
  public Response delete(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @QueryParam("force") @DefaultValue("false") boolean force) {
    StackRef ref =
        resolver
            .resolve(org, project, stack)
            // Tenant guard: cross-org deletes collapse to 404, matching GET.
            .filter(r -> r.orgId().equals(currentPrincipal.get().orgId()))
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));

    deleter.delete(ref, force);
    return Response.ok(Map.of()).build();
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record StackHeadDto(
      @JsonProperty("activeUpdate") String activeUpdate,
      @JsonProperty("currentOperation") Object currentOperation,
      @JsonProperty("orgName") String orgName,
      @JsonProperty("projectName") String projectName,
      @JsonProperty("stackName") String stackName,
      @JsonProperty("tags") Map<String, String> tags) {
    public StackHeadDto(
        String activeUpdate,
        String orgName,
        String projectName,
        String stackName,
        Map<String, String> tags) {
      this(activeUpdate, null, orgName, projectName, stackName, tags);
    }
  }
}
