package io.statedb.pulumiapi.error;

import io.statedb.persistence.RequestValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps every request-validation failure to HTTP 400 with the Pulumi-style {@code {code, message}}.
 */
@Provider
public class RequestValidationExceptionMapper
    implements ExceptionMapper<RequestValidationException> {

  @Override
  public Response toResponse(RequestValidationException exception) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 400, "message", exception.getMessage()))
        .build();
  }
}
