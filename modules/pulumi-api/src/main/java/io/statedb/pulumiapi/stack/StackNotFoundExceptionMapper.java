package io.statedb.pulumiapi.stack;

import io.statedb.persistence.stack.StackNotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class StackNotFoundExceptionMapper implements ExceptionMapper<StackNotFoundException> {

  @Override
  public Response toResponse(StackNotFoundException e) {
    return Response.status(Response.Status.NOT_FOUND)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("message", e.getMessage()))
        .build();
  }
}
