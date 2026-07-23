package io.statedb.consoleapi.workspace;

import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.StackEntityLoader;
import io.statedb.persistence.stack.StackDeleter;
import io.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** Operational stack mutations initiated by the authenticated web console. */
@Path("/api")
@SessionPrincipal
public class StackOperationsResource {

  @Inject AuthorizedResource authorized;
  @Inject StackDeleter deleter;

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
