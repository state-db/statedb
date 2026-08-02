package app.statedb.consoleapi.serviceaccount;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.consoleapi.serviceaccount.ServiceAccountsResource.ServiceAccountDto;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.ServiceAccountFinder;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/** Authorization-filtered service-account inventory shared by org owners and site admins. */
@Path("/api/service-accounts")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class ServiceAccountInventoryResource {

  @Inject CurrentPrincipal currentPrincipal;
  @Inject AdminAllowList adminAllowList;
  @Inject ServiceAccountFinder finder;

  @GET
  public List<ServiceAccountDto> list(@QueryParam("status") String requestedStatus) {
    String status =
        requestedStatus == null || requestedStatus.isBlank() ? "active" : requestedStatus;
    if (!List.of("active", "inactive", "all").contains(status)) {
      throw new RequestValidationException("status must be active, inactive, or all");
    }
    var actor = currentPrincipal.get();
    boolean siteAdmin = adminAllowList.isAdmin(actor.iss(), actor.sub());
    if (!siteAdmin
        && OrgMember.count("userId = ?1 and role = ?2", actor.userId(), OrgMember.Role.owner)
            == 0) {
      throw new ForbiddenException();
    }
    Instant now = Instant.now();
    return finder.manageableBy(actor.userId(), siteAdmin).stream()
        .filter(
            view -> {
              boolean inactive =
                  view.tokenRevokedAt() != null
                      || view.accountDeactivatedAt() != null
                      || !view.tokenExpiresAt().isAfter(now);
              return switch (status) {
                case "active" -> !inactive;
                case "inactive" -> inactive;
                default -> true;
              };
            })
        .map(ServiceAccountDto::from)
        .toList();
  }
}
