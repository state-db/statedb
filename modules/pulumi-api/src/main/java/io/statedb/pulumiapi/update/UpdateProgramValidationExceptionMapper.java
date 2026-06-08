package io.statedb.pulumiapi.update;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class UpdateProgramValidationExceptionMapper
    implements ExceptionMapper<UpdateProgramValidationException> {

  @Override
  public Response toResponse(UpdateProgramValidationException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 400, "message", e.getMessage()))
        .build();
  }
}
