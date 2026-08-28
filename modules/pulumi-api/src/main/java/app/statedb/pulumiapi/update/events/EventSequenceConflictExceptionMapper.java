package app.statedb.pulumiapi.update.events;

import app.statedb.persistence.events.EventSequenceConflictException;
import app.statedb.pulumiapi.error.PulumiErrorResponses;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class EventSequenceConflictExceptionMapper
    implements ExceptionMapper<EventSequenceConflictException> {

  @Override
  public Response toResponse(EventSequenceConflictException e) {
    return PulumiErrorResponses.conflict(e.getMessage());
  }
}
