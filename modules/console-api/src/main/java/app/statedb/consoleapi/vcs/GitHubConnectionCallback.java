package app.statedb.consoleapi.vcs;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.vcs.VcsAuthFlow.Purpose;
import app.statedb.persistence.vcs.VcsAuthFlows;
import app.statedb.persistence.vcs.VcsPublicUrl;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.util.UUID;

/** Fixed GitHub callback URLs route to an org-scoped endpoint that rechecks Cedar authority. */
@Path("/api/vcs/github")
@SessionPrincipal
public class GitHubConnectionCallback {

  @Inject VcsAuthFlows flows;
  @Inject VcsPublicUrl publicUrl;
  @Inject CurrentPrincipal principal;

  @GET
  @Path("/setup")
  public Response setup(
      @QueryParam("state") String state, @QueryParam("installation_id") Long installationId) {
    // GitHub's Manage repositories action can return without a state. It grants no connection.
    if (state == null || state.isBlank()) {
      return Response.seeOther(
              publicUrl.resolve("console/integrations?github=installation-updated"))
          .header("Cache-Control", "no-store")
          .header("Referrer-Policy", "no-referrer")
          .build();
    }
    UUID org = flows.organization(state, principal.get().userId(), Purpose.install);
    return redirect(org, "setup", state, "installation_id", installationId);
  }

  @GET
  @Path("/callback")
  public Response callback(@QueryParam("state") String state, @QueryParam("code") String code) {
    UUID org = flows.organization(state, principal.get().userId(), Purpose.oauth);
    return redirect(org, "callback", state, "code", code);
  }

  private Response redirect(UUID org, String step, String state, String key, Object value) {
    UriBuilder target =
        UriBuilder.fromUri(publicUrl.resolve("api/orgs/" + org + "/vcs/" + step))
            .queryParam("state", state);
    if (value != null) target.queryParam(key, value);
    return Response.seeOther(target.build())
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .build();
  }
}
