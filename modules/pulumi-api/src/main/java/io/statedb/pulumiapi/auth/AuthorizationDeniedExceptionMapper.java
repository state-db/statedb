package io.statedb.pulumiapi.auth;

import io.statedb.persistence.authz.AuthorizationDeniedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AuthorizationDeniedExceptionMapper
    implements ExceptionMapper<AuthorizationDeniedException> {

  @Override
  public Response toResponse(AuthorizationDeniedException exception) {
    return Response.status(Response.Status.FORBIDDEN).build();
  }
}
