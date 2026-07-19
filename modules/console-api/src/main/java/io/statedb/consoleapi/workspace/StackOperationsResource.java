package io.statedb.consoleapi.workspace;

import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.StackEntityLoader;
import io.statedb.persistence.lock.StackLock;
import io.statedb.persistence.stack.CompactionJobEnqueuer;
import io.statedb.persistence.stack.StackDeleter;
import io.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** Operational stack mutations initiated by the authenticated web console. */
@Path("/api")
@SessionPrincipal
public class StackOperationsResource {

  @Inject AuthorizedResource authorized;
  @Inject StackDeleter deleter;
  @Inject CompactionJobEnqueuer compactionJobs;

  @DELETE
  @Path("/admin/stacks/{org}/{project}/{stack}/lock")
  @Authorize(action = "stack:Unlock", loader = StackEntityLoader.class)
  @Transactional
  public Response forceUnlock(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackRef ref = authorized.subject(StackRef.class);
    StackLock.delete("stackId", ref.stackId());
    return Response.noContent().build();
  }

  @POST
  @Path("/admin/stacks/{org}/{project}/{stack}/compact")
  @Authorize(action = "compaction:Trigger", loader = StackEntityLoader.class)
  @Transactional
  public Response compact(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    compactionJobs.enqueue(authorized.subject(StackRef.class).stackId());
    return Response.accepted().build();
  }

  @DELETE
  @Path("/pulumi/stacks/{org}/{project}/{stack}")
  @Authorize(action = "stack:Delete", loader = StackEntityLoader.class)
  public Response delete(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    deleter.delete(authorized.subject(StackRef.class), false);
    return Response.noContent().build();
  }
}
