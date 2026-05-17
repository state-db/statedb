package io.statedb.pulumiapi.stack;

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
import java.util.Map;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class StackResource {

  @Inject StackRefResolver resolver;

  @Inject CurrentPrincipal currentPrincipal;

  // Slice 8 will replace this with the full StackHeadFinder projection. For Slice 7's manual
  // verification (pulumi stack init), the CLI does a pre-flight GET expecting 404 when the
  // stack does not yet exist; without that the CLI panics on the placeholder response.
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> get(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackRef ref =
        resolver
            .resolve(org, project, stack)
            // Tenant guard: cross-org reads collapse to 404 (per db-schema.md), never 403 —
            // a 403 would confirm the target stack exists in another org.
            .filter(r -> r.orgId().equals(currentPrincipal.get().orgId()))
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));
    return Map.of(
        "orgName", ref.orgName(),
        "projectName", ref.projectName(),
        "stackName", ref.stackName());
  }
}
