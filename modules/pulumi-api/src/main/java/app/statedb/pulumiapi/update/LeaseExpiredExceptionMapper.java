package app.statedb.pulumiapi.update;

import app.statedb.persistence.lock.LeaseExpiredException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

// 409 matches StackLockedException — the wire shape Pulumi expects when a lease is no longer
// valid for the requested mutation.
@Provider
public class LeaseExpiredExceptionMapper implements ExceptionMapper<LeaseExpiredException> {

  @Override
  public Response toResponse(LeaseExpiredException e) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 409, "message", e.getMessage()))
        .build();
  }
}
