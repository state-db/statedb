package app.statedb.pulumiapi.update;

import app.statedb.persistence.lock.StackLockedException;
import app.statedb.pulumiapi.error.PulumiErrorResponses;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class StackLockedExceptionMapper implements ExceptionMapper<StackLockedException> {

  @Override
  public Response toResponse(StackLockedException e) {
    return PulumiErrorResponses.conflict(e.getMessage());
  }
}
