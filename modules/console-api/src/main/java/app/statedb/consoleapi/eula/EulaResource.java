package app.statedb.consoleapi.eula;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.authz.SiteEntityLoader;
import app.statedb.persistence.eula.EulaAcknowledgment;
import app.statedb.persistence.eula.EulaDocument;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.UUID;

/**
 * Site-admin view and acceptance of the End User License Agreement. Until an administrator accepts
 * the version this image ships, full functionality is not enabled. See {@code EulaAcknowledgment}
 * and the gate in {@code StackCreator}.
 */
@Path("/api/admin/eula")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class EulaResource {

  @Inject EulaDocument document;
  @Inject EulaAcknowledgment acknowledgment;
  @Inject CurrentPrincipal currentPrincipal;
  @Context HttpHeaders headers;
  @Context RoutingContext routingContext;

  @GET
  @Authorize(action = "eula:Read", loader = SiteEntityLoader.class)
  public EulaDto get() {
    EulaAcknowledgment.EulaStatus status = acknowledgment.status();
    return dto(status);
  }

  /**
   * Accepting is idempotent. The submitted {@code version} must match the version this image ships,
   * so a console page left open across an upgrade cannot accept a superseded agreement.
   */
  @POST
  @Path("/acceptance")
  @Consumes(MediaType.APPLICATION_JSON)
  @Authorize(action = "eula:Accept", loader = SiteEntityLoader.class)
  public EulaDto accept(AcceptRequest request) {
    if (request == null || request.version() == null || request.version().isBlank()) {
      throw new BadRequestException("version is required");
    }
    if (!document.version().equals(request.version())) {
      throw new BadRequestException(
          "this deployment ships End User License Agreement version %s, not %s; reload the console"
              .formatted(document.version(), request.version()));
    }
    UUID userId = currentPrincipal.get().userId();
    EulaAcknowledgment.EulaStatus status = acknowledgment.accept(userId, clientAddress());
    return dto(status);
  }

  /**
   * Best-effort client address for the acceptance record. A deployment behind a proxy that does not
   * set {@code X-Forwarded-For} records the peer address. An unknown address records nothing; the
   * accepting user and timestamp are the evidence that matters.
   */
  private String clientAddress() {
    String forwarded = headers.getHeaderString("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    SocketAddress remote = routingContext == null ? null : routingContext.request().remoteAddress();
    return remote == null ? null : remote.hostAddress();
  }

  private EulaDto dto(EulaAcknowledgment.EulaStatus status) {
    return new EulaDto(
        status.version(),
        status.documentSha256(),
        status.accepted(),
        status.acceptedVia() == null ? null : status.acceptedVia().name(),
        status.acceptedByUserId(),
        status.acceptedAt(),
        document.text());
  }

  public record AcceptRequest(String version) {}

  public record EulaDto(
      String version,
      String documentSha256,
      boolean accepted,
      String acceptedVia,
      UUID acceptedByUserId,
      Instant acceptedAt,
      String text) {}
}
