package app.statedb.consoleapi.org;

import app.statedb.persistence.identity.LastOwnerException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class LastOwnerExceptionMapper implements ExceptionMapper<LastOwnerException> {
  public Response toResponse(LastOwnerException e) {
    return Response.status(Response.Status.CONFLICT)
        .entity(Map.of("error", e.getMessage()))
        .build();
  }
}
