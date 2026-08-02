package app.statedb.pulumiapi.stack;

import app.statedb.persistence.stack.StackNotEmptyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class StackNotEmptyExceptionMapper implements ExceptionMapper<StackNotEmptyException> {

  @Override
  public Response toResponse(StackNotEmptyException e) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("message", e.getMessage(), "detail", e.qualifiedName()))
        .build();
  }
}
