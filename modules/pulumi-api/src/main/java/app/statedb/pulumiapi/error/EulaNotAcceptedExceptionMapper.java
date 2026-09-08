package app.statedb.pulumiapi.error;

import app.statedb.persistence.eula.EulaNotAcceptedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Renders the EULA gate as an {@code apitype.ErrorResponse} 403, so the Pulumi CLI prints the
 * message that tells the operator to accept the agreement in the console.
 */
@Provider
public class EulaNotAcceptedExceptionMapper implements ExceptionMapper<EulaNotAcceptedException> {
  @Override
  public Response toResponse(EulaNotAcceptedException exception) {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            Map.of(
                "code", 403,
                "message", exception.getMessage(),
                "eulaVersion", exception.requiredVersion()))
        .build();
  }
}
