package app.statedb.pulumiapi.update.journal;

import app.statedb.persistence.journal.JournalSequenceConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class JournalSequenceConflictExceptionMapper
    implements ExceptionMapper<JournalSequenceConflictException> {

  @Override
  public Response toResponse(JournalSequenceConflictException e) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 409, "message", e.getMessage()))
        .build();
  }
}
