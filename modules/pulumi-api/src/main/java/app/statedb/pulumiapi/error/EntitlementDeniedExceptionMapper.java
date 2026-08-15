package app.statedb.pulumiapi.error;

import app.statedb.persistence.entitlement.EntitlementDeniedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class EntitlementDeniedExceptionMapper
    implements ExceptionMapper<EntitlementDeniedException> {
  @Override
  public Response toResponse(EntitlementDeniedException exception) {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            Map.of(
                "code", 403,
                "message", exception.getMessage(),
                "stackCount", exception.stackCount(),
                "stackLimit", exception.limit(),
                "licenseStatus", exception.licenseStatus().wireValue()))
        .build();
  }
}
