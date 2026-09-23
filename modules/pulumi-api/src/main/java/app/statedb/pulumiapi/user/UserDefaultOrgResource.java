package app.statedb.pulumiapi.user;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.identity.OrgMembershipFinder;
import app.statedb.persistence.identity.OrgMembershipView;
import app.statedb.persistence.identity.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;

@Path("/pulumi/api/user/organizations/default")
public class UserDefaultOrgResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject OrgMembershipFinder orgMembershipFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public DefaultOrgDto get() {
    AuthenticatedPrincipal p = currentPrincipal.get();
    return new DefaultOrgDto(defaultOrg(p));
  }

  // Single-org credential → its scoped org. All-current-memberships credential → the actor's first
  // org by name (the same ordering the console uses); with no memberships, fall back to sub.
  private String defaultOrg(AuthenticatedPrincipal p) {
    Optional<String> singleOrgName = p.credentialScope().singleOrgName();
    if (singleOrgName.isPresent()) {
      return singleOrgName.get();
    }
    List<OrgMembershipView> memberships = orgMembershipFinder.forUser(p.userId());
    if (!memberships.isEmpty()) {
      return memberships.get(0).name();
    }
    User user = User.findById(p.userId());
    return user.sub;
  }

  // apitype.GetDefaultOrganizationResponse has no JSON tags, so the CLI reads a top-level
  // GitHubLogin; any other shape is ignored and unqualified stacks fall back to `whoami`.
  public record DefaultOrgDto(@JsonProperty("GitHubLogin") String gitHubLogin) {}
}
