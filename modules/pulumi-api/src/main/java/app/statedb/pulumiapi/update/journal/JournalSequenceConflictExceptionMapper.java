package app.statedb.pulumiapi.update.journal;

import app.statedb.persistence.journal.JournalSequenceConflictException;
import app.statedb.pulumiapi.error.PulumiErrorResponses;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class JournalSequenceConflictExceptionMapper
    implements ExceptionMapper<JournalSequenceConflictException> {

  @Override
  public Response toResponse(JournalSequenceConflictException e) {
    return PulumiErrorResponses.conflict(e.getMessage());
  }
}
