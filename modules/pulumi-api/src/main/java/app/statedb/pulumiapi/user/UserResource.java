package app.statedb.pulumiapi.user;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.identity.OrgMembershipFinder;
import app.statedb.persistence.identity.User;
import app.statedb.pulumiapi.wire.OrganizationReference;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    // org. Unqualified stack names target this personal namespace; service-account automation
    // should create stacks with a fully qualified <org>/<project>/<stack> name.
    return new UserDto(user.email, cliLogin(user), user.displayName, organizations(p));
  }

  // `sub` is an opaque IdP identifier — a bare UUID on most providers — so prefer anything a person
  // would recognize. The recorded fixture reports the display name here too.
  private static String cliLogin(User user) {
    if (user.displayName != null && !user.displayName.isBlank()) {
      return user.displayName;
    }
    if (user.email != null && !user.email.isBlank()) {
      return user.email;
    }
    return user.sub;
  }

  // Single-org credentials report exactly their scoped org. All-current-memberships credentials
  // report every org the actor belongs to, so the CLI can address stacks across all of them.
  private List<OrganizationReference> organizations(AuthenticatedPrincipal p) {
    Optional<String> singleOrgName = p.credentialScope().singleOrgName();
    if (singleOrgName.isPresent()) {
      return List.of(new OrganizationReference(singleOrgName.get(), singleOrgName.get()));
    }
    return orgMembershipFinder.forUser(p.userId()).stream()
        .map(m -> new OrganizationReference(m.name(), m.name()))
        .toList();
  }

  public record UserDto(
      @JsonProperty("email") String email,
      @JsonProperty("githubLogin") String githubLogin,
      @JsonProperty("name") String name,
      @JsonProperty("organizations") List<OrganizationReference> organizations) {}
}
