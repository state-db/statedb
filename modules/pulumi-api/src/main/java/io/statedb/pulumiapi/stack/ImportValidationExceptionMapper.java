package io.statedb.pulumiapi.stack;

import io.statedb.persistence.stack.ImportValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class ImportValidationExceptionMapper implements ExceptionMapper<ImportValidationException> {

  @Override
  public Response toResponse(ImportValidationException exception) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 400, "message", exception.getMessage()))
        .build();
  }
}
