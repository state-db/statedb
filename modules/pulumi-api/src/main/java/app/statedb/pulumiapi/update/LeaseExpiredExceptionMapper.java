package app.statedb.pulumiapi.update;

import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.pulumiapi.error.PulumiErrorResponses;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class LeaseExpiredExceptionMapper implements ExceptionMapper<LeaseExpiredException> {

  @Override
  public Response toResponse(LeaseExpiredException e) {
    return PulumiErrorResponses.conflict(e.getMessage());
  }
}
