package io.statedb.pulumiapi.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.statedb.persistence.identity.AuthenticatedPrincipal;
import io.statedb.pulumiapi.auth.CurrentPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/pulumi/api/user/organizations/default")
public class UserDefaultOrgResource {

  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public DefaultOrgDto get() {
    AuthenticatedPrincipal p = currentPrincipal.get();
    return new DefaultOrgDto(new OrgRef(p.orgName(), p.orgName()));
  }

  public record DefaultOrgDto(@JsonProperty("organization") OrgRef organization) {}

  public record OrgRef(
      @JsonProperty("githubLogin") String githubLogin, @JsonProperty("name") String name) {}
}
