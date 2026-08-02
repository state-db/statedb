package app.statedb.consoleapi.user;

import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.OrgMembershipFinder;
import app.statedb.persistence.identity.User;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Returns the user represented by the authenticated OIDC session. */
@Path("/api/user")
public class CurrentUserResource {

  @Inject SecurityIdentity identity;

  @Inject AdminAllowList adminAllowList;

  @Inject OrgMembershipFinder orgMembershipFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public UserResponse get() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      throw new NotAuthorizedException("OIDC");
    }

    User user =
        User.<User>find("iss = ?1 and sub = ?2", jwt.getIssuer(), jwt.getSubject())
            .firstResultOptional()
            .filter(candidate -> candidate.deactivatedAt == null)
            .orElseThrow(() -> new NotAuthorizedException("OIDC"));
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
        adminAllowList.isAdmin(jwt.getIssuer(), jwt.getSubject()),
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
