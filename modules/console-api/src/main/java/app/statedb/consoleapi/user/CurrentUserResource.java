package app.statedb.consoleapi.user;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.OrgMembershipFinder;
import app.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

/** Returns the user represented by the authenticated OIDC session. */
@Path("/api/user")
@SessionPrincipal
public class CurrentUserResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject AdminAllowList adminAllowList;

  @Inject OrgMembershipFinder orgMembershipFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse get() {
    AuthenticatedPrincipal principal = currentPrincipal.get();
    User user = User.findById(principal.userId());
    List<OrganizationResponse> organizations =
        orgMembershipFinder.forUser(user.id).stream()
            .map(
                membership ->
                    new OrganizationResponse(
                        membership.name(), membership.displayName(), membership.role()))
            .toList();

    return new UserResponse(
        user.id,
        user.email,
        user.displayName,
        adminAllowList.isAdmin(principal.iss(), principal.sub()),
        organizations);
  }

  public record UserResponse(
      UUID id,
      String email,
      String name,
      boolean siteAdmin,
      List<OrganizationResponse> organizations) {}

  public record OrganizationResponse(String name, String displayName, OrgMember.Role role) {}
}
