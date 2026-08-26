package app.statedb.consoleapi.workspace;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.StackEntityLoader;
import app.statedb.persistence.stack.StackDeleter;
import app.statedb.persistence.stack.StackRef;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
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
  public Response delete() {
    deleter.delete(authorized.subject(StackRef.class), false);
    return Response.noContent().build();
  }
}
