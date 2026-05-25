package io.statedb.pulumiapi.update;

import io.statedb.persistence.update.CompleteValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class CompleteValidationExceptionMapper
    implements ExceptionMapper<CompleteValidationException> {

  @Override
  public Response toResponse(CompleteValidationException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 400, "message", e.getMessage()))
        .build();
  }
}
