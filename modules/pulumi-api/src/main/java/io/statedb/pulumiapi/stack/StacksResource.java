package io.statedb.pulumiapi.stack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/pulumi/api/stacks/{org}/{project}")
public class StacksResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject StackCreator stackCreator;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public StackDto create(
      @PathParam("org") String org,
      @PathParam("project") String project,
      CreateStackRequest body) {
    AuthenticatedPrincipal principal = currentPrincipal.get();
    // Pre-Cedar: direct comparison of path org against the principal's org.
    if (!org.equals(principal.orgName())) {
      throw new WebApplicationException(Response.Status.FORBIDDEN);
    }
    if (body == null || body.stackName == null || body.stackName.isBlank()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }

    stackCreator.create(principal.orgId(), org, project, body.stackName, body.tags);
    return new StackDto(org, project, body.stackName, body.tags);
  }

  public static class CreateStackRequest {
    @JsonProperty("stackName")
    public String stackName;

    @JsonProperty("tags")
    public Map<String, String> tags;
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record StackDto(
      @JsonProperty("activeUpdate") String activeUpdate,
      @JsonProperty("currentOperation") Object currentOperation,
      @JsonProperty("orgName") String orgName,
      @JsonProperty("projectName") String projectName,
      @JsonProperty("stackName") String stackName,
      @JsonProperty("tags") Map<String, String> tags) {
    public StackDto(String orgName, String projectName, String stackName, Map<String, String> tags) {
      this("", null, orgName, projectName, stackName, tags);
    }
  }
}
