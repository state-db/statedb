package io.statedb.pulumiapi.update;

import io.statedb.persistence.lock.Lease;
import io.statedb.persistence.lock.LeaseContext;
import io.statedb.persistence.lock.StackLockAcquirer;
import io.statedb.persistence.lock.StackLockCanceller;
import io.statedb.persistence.lock.StackLockedException;
import io.statedb.persistence.stack.StackNotFoundException;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import io.statedb.persistence.update.StartedUpdate;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import io.statedb.pulumiapi.auth.LeaseContextHolder;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class UpdateResource {

  @Inject StackRefResolver resolver;

  @Inject StackUpdateStarter starter;

  @Inject StackLockAcquirer lockAcquirer;

  @Inject StackLockCanceller lockCanceller;

  @Inject CurrentPrincipal currentPrincipal;

  @Inject LeaseContextHolder leaseHolder;

  @POST
  @Path("/update")
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
  @Path("/update/{updateID}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public AcquireLockResponse acquireLock(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateID") String updateID,
      AcquireLockRequest body) {
    StackRef ref = resolveOrThrow(org, project, stack);
    UUID updateId = parseUpdateIdOr404(updateID, org, project, stack);

    // Confirm the update belongs to this stack — defends against cross-stack updateID smuggling.
    StackUpdate existing =
        StackUpdate.<StackUpdate>find("id = ?1 and stackId = ?2", updateId, ref.stackId())
            .firstResultOptional()
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));

    String lockedBy = "user:" + currentPrincipal.get().userId();
    AcquireLockRequest safe = body != null ? body : new AcquireLockRequest();
    Optional<Lease> lease =
        lockAcquirer.acquire(
            existing.stackId, existing.id, lockedBy, safe.journalVersion, safe.tags);

    Lease l = lease.orElseThrow(() -> new StackLockedException(existing.stackId));
    return new AcquireLockResponse(l.lockToken().toString(), l.duration().toSeconds());
  }

  @DELETE
  @Path("/update/{updateID}")
  public Response cancelUpdate(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("updateID") String updateID) {
    // LeaseTokenFilter has already validated the update-token against the lock row, and
    // confirmed both the path stack and the path updateID match. Re-extract the lease
    // context here as a defensive check before mutating state.
    LeaseContext ctx = leaseHolder.get();
    UUID pathUpdateId = parseUpdateIdOr404(updateID, org, project, stack);
    if (ctx == null || !pathUpdateId.equals(ctx.updateId())) {
      return Response.status(Response.Status.CONFLICT).build();
    }
    lockCanceller.cancel(ctx.stackId(), ctx.updateId());
    return Response.noContent().build();
  }

  private StackRef resolveOrThrow(String org, String project, String stack) {
    return resolver
        .resolve(org, project, stack)
        .filter(r -> r.orgId().equals(currentPrincipal.get().orgId()))
        .orElseThrow(() -> new StackNotFoundException(org, project, stack));
  }

  private static UUID parseUpdateIdOr404(
      String raw, String org, String project, String stack) {
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
    StackRef ref =
        resolver
            .resolve(org, project, stack)
            // Cross-org collapses to 404, never 403.
            .filter(r -> r.orgId().equals(currentPrincipal.get().orgId()))
            .orElseThrow(() -> new StackNotFoundException(org, project, stack));

    UpdateProgramRequest safe = body != null ? body : new UpdateProgramRequest();
    StartUpdateRequest req =
        new StartUpdateRequest(
            safe.config,
            safe.metadata,
            safe.options,
            safe.description,
            safe.runtime,
            pulumiVersion(safe.metadata),
            message(safe.metadata));

    StartedUpdate started = starter.start(ref.stackId(), kind, idempotencyKey, req);
    return new StartUpdateResponse(started.updateId().toString(), started.version());
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
}
