package io.statedb.pulumiapi.update.events;

import io.statedb.persistence.events.EventBatchValidationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class EventBatchValidationExceptionMapper
    implements ExceptionMapper<EventBatchValidationException> {

  @Override
  public Response toResponse(EventBatchValidationException e) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 400, "message", e.getMessage()))
        .build();
  }
}
