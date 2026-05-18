package io.statedb.pulumiapi.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.stack.StackListFinder;
import io.statedb.persistence.stack.StackSummary;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/pulumi/api/user/stacks")
public class UserStacksResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject StackListFinder finder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public StackListDto list(@QueryParam("project") String project) {
    AuthenticatedPrincipal p = currentPrincipal.get();
    // Cross-org rows are invisible (not 403'd), matching StackResource's isolation rule: a 403
    // would confirm the target row exists in another tenant.
    String projectFilter = (project == null || project.isBlank()) ? null : project;
    List<StackSummary> rows = finder.findForOrg(p.orgId(), projectFilter);
    List<StackSummaryDto> dtos =
        rows.stream()
            .map(
                r ->
                    new StackSummaryDto(
                        r.orgName() + "/" + r.projectName() + "/" + r.stackName(),
                        r.orgName(),
                        r.projectName(),
                        r.stackName(),
                        r.lastUpdate(),
                        r.resourceCount()))
            .toList();
    return new StackListDto(dtos);
  }

  public record StackListDto(@JsonProperty("stacks") List<StackSummaryDto> stacks) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StackSummaryDto(
      @JsonProperty("id") String id,
      @JsonProperty("orgName") String orgName,
      @JsonProperty("projectName") String projectName,
      @JsonProperty("stackName") String stackName,
      @JsonProperty("lastUpdate") Long lastUpdate,
      @JsonProperty("resourceCount") Long resourceCount) {}
}
