package app.statedb.pulumiapi.update.events;

import app.statedb.persistence.events.EventSequenceConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class EventSequenceConflictExceptionMapper
    implements ExceptionMapper<EventSequenceConflictException> {

  @Override
  public Response toResponse(EventSequenceConflictException e) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 409, "message", e.getMessage()))
        .build();
  }
}
