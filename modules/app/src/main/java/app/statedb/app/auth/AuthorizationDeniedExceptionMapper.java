package app.statedb.app.auth;

import app.statedb.persistence.authz.AuthorizationDeniedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Maps Cedar denials from either API surface to the shared empty 403 response. */
@Provider
public class AuthorizationDeniedExceptionMapper
    implements ExceptionMapper<AuthorizationDeniedException> {

  @Override
  public Response toResponse(AuthorizationDeniedException exception) {
    return Response.status(Response.Status.FORBIDDEN).build();
  }
}
