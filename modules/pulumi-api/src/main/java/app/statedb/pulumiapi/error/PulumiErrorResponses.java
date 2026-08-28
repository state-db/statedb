package app.statedb.pulumiapi.error;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** Response factories for error shapes interpreted by the Pulumi CLI. */
public final class PulumiErrorResponses {

  private PulumiErrorResponses() {}

  /**
   * Pulumi decodes failed service calls as {@code apitype.ErrorResponse}; update acquisition also
   * uses the body's {@code code}, rather than only the HTTP status, to recognize a conflicting
   * update. Messages remain owned by the failing operation because their useful detail differs.
   */
  public static Response conflict(String message) {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 409, "message", message))
        .build();
  }
}
