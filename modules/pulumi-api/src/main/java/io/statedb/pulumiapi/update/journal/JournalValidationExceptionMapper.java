package io.statedb.pulumiapi.update.journal;

import io.statedb.persistence.journal.JournalValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class JournalValidationExceptionMapper
    implements ExceptionMapper<JournalValidationException> {

  @Override
  public Response toResponse(JournalValidationException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 400, "message", e.getMessage()))
        .build();
  }
}
