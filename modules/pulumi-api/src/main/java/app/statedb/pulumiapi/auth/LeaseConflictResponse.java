package app.statedb.pulumiapi.auth;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** Pulumi wire response for an update lease that cannot authorize the requested mutation. */
public final class LeaseConflictResponse {

  private LeaseConflictResponse() {}

  public static Response response() {
    return Response.status(Response.Status.CONFLICT)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 409, "message", "update lease is no longer valid"))
        .build();
  }
}
