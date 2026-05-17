package io.statedb.pulumiapi.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.identity.User;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/pulumi/api/user")
public class UserResource {

  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public UserDto get() {
    AuthenticatedPrincipal p = currentPrincipal.get();
    User user = User.findById(p.userId());
    // Top-level githubLogin is what `pulumi whoami` prints — the user's account identity, not the
    // org. We have no GitHub linkage, so use the OIDC subject as a stable per-user login.
    return new UserDto(
        user.email, user.sub, user.displayName, List.of(new OrgRef(p.orgName(), p.orgName())));
  }

  public record UserDto(
      @JsonProperty("email") String email,
      @JsonProperty("githubLogin") String githubLogin,
      @JsonProperty("name") String name,
      @JsonProperty("organizations") List<OrgRef> organizations) {}

  public record OrgRef(
      @JsonProperty("githubLogin") String githubLogin, @JsonProperty("name") String name) {}
}
