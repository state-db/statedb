package io.statedb.pulumiapi.update;

import io.statedb.persistence.stack.StackNotFoundException;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import io.statedb.persistence.update.StartedUpdate;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}")
public class UpdateResource {

  @Inject StackRefResolver resolver;

  @Inject StackUpdateStarter starter;

  @Inject CurrentPrincipal currentPrincipal;

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
