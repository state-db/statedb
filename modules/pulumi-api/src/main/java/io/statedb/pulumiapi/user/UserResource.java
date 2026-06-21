package io.statedb.pulumiapi.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.authz.CurrentPrincipal;
import io.statedb.persistence.identity.OrgMembershipFinder;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;

@Path("/pulumi/api/user")
public class UserResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject OrgMembershipFinder orgMembershipFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public UserDto get() {
    AuthenticatedPrincipal p = currentPrincipal.get();
    User user = User.findById(p.userId());
    // Top-level githubLogin is what `pulumi whoami` prints — the user's account identity, not the
    // org. Unqualified stack names target this personal namespace; org stacks should be addressed
    // as <org>/<stack>.
    return new UserDto(user.email, user.sub, user.displayName, organizations(p));
  }

  // Single-org credentials report exactly their scoped org. All-current-memberships credentials
  // report every org the actor belongs to, so the CLI can address stacks across all of them.
  private List<OrgRef> organizations(AuthenticatedPrincipal p) {
    Optional<String> singleOrgName = p.credentialScope().singleOrgName();
    if (singleOrgName.isPresent()) {
      return List.of(new OrgRef(singleOrgName.get(), singleOrgName.get()));
    }
    return orgMembershipFinder.forUser(p.userId()).stream()
        .map(m -> new OrgRef(m.name(), m.name()))
        .toList();
  }

  public record UserDto(
      @JsonProperty("email") String email,
      @JsonProperty("githubLogin") String githubLogin,
      @JsonProperty("name") String name,
      @JsonProperty("organizations") List<OrgRef> organizations) {}

  public record OrgRef(
      @JsonProperty("githubLogin") String githubLogin, @JsonProperty("name") String name) {}
}
