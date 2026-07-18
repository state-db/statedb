package io.statedb.pulumiapi.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.authz.CurrentPrincipal;
import io.statedb.persistence.stack.StackListFinder;
import io.statedb.persistence.stack.StackSummary;
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
    // Unreadable rows are invisible (not 403'd), matching StackResource's isolation rule: a 403
    // would confirm the target row exists. The finder's predicate mirrors the Cedar read policies
    // (role floor + visibility + per-stack shares).
    String projectFilter = (project == null || project.isBlank()) ? null : project;
    // Single-org credentials see only their scoped org; all-current-memberships credentials span
    // every org the actor belongs to.
    List<StackSummary> rows =
        p.credentialScope()
            .singleOrgId()
            .map(orgId -> finder.findVisibleToUserInOrg(p.userId(), orgId, projectFilter))
            .orElseGet(() -> finder.findVisibleToUser(p.userId(), projectFilter));
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
