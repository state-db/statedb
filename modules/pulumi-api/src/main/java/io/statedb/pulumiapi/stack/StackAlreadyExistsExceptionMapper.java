package io.statedb.pulumiapi.stack;

import io.statedb.persistence.stack.StackAlreadyExistsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class StackAlreadyExistsExceptionMapper
    implements ExceptionMapper<StackAlreadyExistsException> {

  @Override
  public Response toResponse(StackAlreadyExistsException e) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("message", e.getMessage(), "detail", e.qualifiedName()))
        .build();
  }
}
