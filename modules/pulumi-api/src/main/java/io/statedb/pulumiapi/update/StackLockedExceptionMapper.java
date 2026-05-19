package io.statedb.pulumiapi.update;

import io.statedb.persistence.lock.StackLockedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

// Wire body is a best-effort match for what the Pulumi CLI prints as
// "conflict: stack is currently locked". The exact shape gets pinned once a real fixture is
// captured for a contended acquire.
@Provider
public class StackLockedExceptionMapper implements ExceptionMapper<StackLockedException> {

  @Override
  public Response toResponse(StackLockedException e) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 409, "message", e.getMessage()))
        .build();
  }
}
