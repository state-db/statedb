package app.statedb.pulumiapi.update;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.authz.StackEntityLoader;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.lock.StackLockCanceller;
import app.statedb.persistence.lock.StackLockedException;
import app.statedb.persistence.stack.StackNotFoundException;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import app.statedb.persistence.update.StartedUpdate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Normal-token authenticated lifecycle control for Pulumi updates. */
@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class UpdateResource {

  @Inject AuthorizedResource authorized;

  @Inject StackUpdateStarter starter;

  @Inject StackLockAcquirer lockAcquirer;

  @Inject StackLockCanceller lockCanceller;

  @Inject CurrentPrincipal currentPrincipal;

  @POST
  @Path("/update")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public StartUpdateResponse startUpdate(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      UpdateProgramRequest body) {
    return start(org, project, stack, StackUpdate.Kind.UPDATE, idempotencyKey, body);
  }

  @POST
  @Path("/preview")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public StartUpdateResponse startPreview(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      UpdateProgramRequest body) {
    return start(org, project, stack, StackUpdate.Kind.PREVIEW, idempotencyKey, body);
  }

  @POST
  @Path("/destroy")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public StartUpdateResponse startDestroy(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      UpdateProgramRequest body) {
    return start(org, project, stack, StackUpdate.Kind.DESTROY, idempotencyKey, body);
  }

  @POST
  @Path("/refresh")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public StartUpdateResponse startRefresh(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      UpdateProgramRequest body) {
    return start(org, project, stack, StackUpdate.Kind.REFRESH, idempotencyKey, body);
  }

  @POST
  @Path("/update/{updateID}")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public AcquireLockResponse acquireLock(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateID") String updateID,
      AcquireLockRequest body) {
    StackRef ref = authorized.subject(StackRef.class);
    UUID updateId = parseUpdateIdOr404(updateID, org, project, stack);

    // Confirm the update belongs to this stack — defends against cross-stack updateID smuggling.
    StackUpdate existing =
        StackUpdate.<StackUpdate>find("id = ?1 and stackId = ?2", updateId, ref.stackId())
            .firstResultOptional()
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));

    String lockedBy = "user:" + currentPrincipal.get().userId();
    AcquireLockRequest safe = body != null ? body : new AcquireLockRequest();
    validateAcquireRequest(project, safe);
    Optional<Lease> lease =
        lockAcquirer.acquire(
            existing.stackId, existing.id, lockedBy, safe.journalVersion, safe.tags);

    Lease l = lease.orElseThrow(() -> new StackLockedException(existing.stackId));
    return new AcquireLockResponse(
        l.plaintextToken().toString(),
        l.expiresAt().getEpochSecond(),
        existing.version,
        safe.journalVersion);
  }

  @GET
  @Path("/update/{updateID}")
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public UpdateResultsResponse getUpdate(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateID") String updateID) {
    StackRef ref = authorized.subject(StackRef.class);
    UUID updateId = parseUpdateIdOr404(updateID, org, project, stack);
    StackUpdate update =
        StackUpdate.<StackUpdate>find("id = ?1 and stackId = ?2", updateId, ref.stackId())
            .firstResultOptional()
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));

    String status =
        switch (update.status) {
          case IN_PROGRESS -> "running";
          case SUCCEEDED -> "succeeded";
          case FAILED, CANCELLED -> "failed";
        };
    String continuationToken = update.status == StackUpdate.Status.IN_PROGRESS ? "0" : null;
    return new UpdateResultsResponse(status, List.of(), continuationToken);
  }

  @POST
  @Path("/update/{updateID}/cancel")
  @Authorize(action = "stack:Lock", loader = StackEntityLoader.class)
  public Response forceCancelUpdate(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateID") String updateID) {
    StackRef ref = authorized.subject(StackRef.class);
    UUID pathUpdateId = parseUpdateIdOr404(updateID, org, project, stack);
    if (!lockCanceller.forceCancelUpdate(ref.stackId(), pathUpdateId)) {
      return updateNotFound(updateID);
    }
    return Response.noContent().build();
  }

  private static Response updateNotFound(String updateID) {
    return Response.status(Response.Status.NOT_FOUND)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("message", "update not found", "detail", updateID))
        .build();
  }

  private static UUID parseUpdateIdOr404(String raw, String org, String project, String stack) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new StackNotFoundException(org, project, stack);
    }
  }

  private StartUpdateResponse start(
      String org,
      String project,
      String stack,
      StackUpdate.Kind kind,
      String idempotencyKey,
      UpdateProgramRequest body) {
    StackRef ref = authorized.subject(StackRef.class);

    UpdateProgramRequest safe = body != null ? body : new UpdateProgramRequest();
    validateStartRequest(org, project, stack, safe);
    StartUpdateRequest req =
        new StartUpdateRequest(
            safe.config,
            safe.metadata,
            safe.options,
            safe.description,
            safe.runtime,
            pulumiVersion(safe.metadata),
            message(safe.metadata));

    StartedUpdate started =
        starter.start(
            ref.stackId(),
            kind,
            currentPrincipal.get().userId(),
            currentPrincipal.get().apiTokenId(),
            idempotencyKey,
            req);
    return new StartUpdateResponse(started.updateId().toString(), started.version());
  }

  private static void validateStartRequest(
      String org, String project, String stack, UpdateProgramRequest body) {
    requireMatches("orgName", body.orgName, org);
    requireMatches("projectName", body.projectName, project);
    requireMatches("name", body.name, project);
    requireMatches("stackName", body.stackName, stack);
  }

  private static void validateAcquireRequest(String project, AcquireLockRequest body) {
    if (body.tags == null) return;
    Object taggedProject = body.tags.get("pulumi:project");
    if (taggedProject == null) return;
    requireMatches("tags.pulumi:project", taggedProject.toString(), project);
  }

  private static void requireMatches(String field, String actual, String expected) {
    if (actual == null || actual.isBlank()) return;
    if (!expected.equals(actual)) {
      throw new RequestValidationException(
          field + " must match the stack path; expected '" + expected + "', got '" + actual + "'");
    }
  }

  @SuppressWarnings("unchecked")
  private static String pulumiVersion(Map<String, Object> metadata) {
    if (metadata == null) return null;
    Object env = metadata.get("environment");
    if (!(env instanceof Map<?, ?> m)) return null;
    Object v = ((Map<String, Object>) m).get("pulumi.version");
    return v == null ? null : v.toString();
  }

  private static String message(Map<String, Object> metadata) {
    if (metadata == null) return null;
    Object m = metadata.get("message");
    return m == null ? null : m.toString();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record UpdateResultsResponse(
      @JsonProperty("status") String status,
      @JsonProperty("events") List<Object> events,
      @JsonProperty("continuationToken") String continuationToken) {}
}
