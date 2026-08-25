package app.statedb.pulumiapi.stack;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.authz.StackEntityLoader;
import app.statedb.persistence.stack.StackDeleter;
import app.statedb.persistence.stack.StackHead;
import app.statedb.persistence.stack.StackHeadFinder;
import app.statedb.persistence.stack.StackImporter;
import app.statedb.persistence.stack.StackNotFoundException;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRenamer;
import app.statedb.persistence.stack.StackTagWriter;
import app.statedb.pulumiapi.wire.StackDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class StackResource {

  @Inject StackHeadFinder finder;

  @Inject AuthorizedResource authorized;

  @Inject StackDeleter deleter;

  @Inject StackImporter importer;

  @Inject StackTagWriter tagWriter;

  @Inject StackRenamer renamer;

  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public StackDto get(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackHead head =
        finder
            .find(org, project, stack)
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));
    return new StackDto(
        head.activeUpdate(),
        null,
        head.orgName(),
        head.projectName(),
        head.stackName(),
        head.tags());
  }

  @DELETE
  @Authorize(action = "stack:Delete", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public Response delete(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @QueryParam("force") @DefaultValue("false") boolean force) {
    StackRef ref = authorized.subject(StackRef.class);

    deleter.delete(ref, force);
    return Response.ok(Map.of()).build();
  }

  @POST
  @Path("/import")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public ImportStackResponse importStack(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      ImportStackRequest body) {
    StackRef ref = authorized.subject(StackRef.class);

    if (body == null) {
      throw new RequestValidationException("import request body is required");
    }
    if (body.version != 3) {
      throw new RequestValidationException("only Pulumi deployment schema version 3 is supported");
    }
    if (body.features != null && !body.features.isEmpty()) {
      throw new RequestValidationException("deployment features are not supported");
    }

    AuthenticatedPrincipal principal = currentPrincipal.get();
    UUID updateId =
        importer.importDeployment(
            ref.stackId(), principal.userId(), principal.apiTokenId(), body.deployment);
    return new ImportStackResponse(updateId.toString());
  }

  // `stack tag set`/`rm` PATCH the full desired tag map (not a delta), so replace wholesale.
  @PATCH
  @Path("/tags")
  @Authorize(action = "stack:SetTags", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response setTags(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      Map<String, String> tags) {
    StackRef ref = authorized.subject(StackRef.class);
    tagWriter.replaceTags(ref.stackId(), tags);
    return Response.ok(Map.of()).build();
  }

  // `stack rename` moves the stack to a new name and/or project. The CLI sends both fields (an
  // empty string preserving the current value). The rename is recorded as a `rename` update; the
  // recorded service contract returns the AppImportStackResponse shape ({updateId}) carrying that
  // update's id (empty only for a no-op rename). The CLI discards the body, but matching the
  // documented shape keeps other clients able to decode it.
  @POST
  @Path("/rename")
  @Authorize(action = "stack:Rename", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public ImportStackResponse rename(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      AppStackRenameRequest body) {
    boolean noName = body == null || body.newName == null || body.newName.isBlank();
    boolean noProject = body == null || body.newProject == null || body.newProject.isBlank();
    if (noName && noProject) {
      throw new RequestValidationException("no rename to apply");
    }
    StackRef ref = authorized.subject(StackRef.class);
    AuthenticatedPrincipal principal = currentPrincipal.get();
    UUID updateId =
        renamer.rename(
            ref, body.newName, body.newProject, principal.userId(), principal.apiTokenId());
    return new ImportStackResponse(updateId == null ? "" : updateId.toString());
  }

  public static class AppStackRenameRequest {
    @JsonProperty("newName")
    public String newName;

    @JsonProperty("newProject")
    public String newProject;
  }

  public static class ImportStackRequest {
    @JsonProperty("version")
    public int version;

    @JsonProperty("deployment")
    public JsonNode deployment;

    @JsonProperty("features")
    public List<String> features;
  }

  // Pulumi's ImportStackResponse uses json:"updateId" even though update lifecycle APIs use
  // "updateID"; keep this spelling or the CLI will not poll the imported update.
  public record ImportStackResponse(@JsonProperty("updateId") String updateId) {}
}
