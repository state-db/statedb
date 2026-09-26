package app.statedb.consoleapi.vcs;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.authz.SiteEntityLoader;
import app.statedb.persistence.vcs.GitHubAppManager;
import app.statedb.persistence.vcs.GitHubAppManager.ManifestSubmission;
import app.statedb.persistence.vcs.VcsAuthFlows;
import app.statedb.persistence.vcs.VcsPublicUrl;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Site-administrator flow for creating the deployment-owned GitHub App at runtime. */
@Path("/api/admin/vcs/github")
@SessionPrincipal
@Authorize(action = "vcs:ManageSite", loader = SiteEntityLoader.class)
@Produces(MediaType.APPLICATION_JSON)
public class GitHubIntegrationResource {

  @Inject GitHubAppManager github;
  @Inject VcsAuthFlows flows;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject VcsPublicUrl publicUrl;

  @GET
  public GitHubAppDto status() {
    return GitHubAppDto.of(publicUrl, github);
  }

  @POST
  @Path("/manifest")
  @Consumes(MediaType.APPLICATION_JSON)
  public ManifestDto begin(ManifestRequest request) {
    var base = publicUrl.base(); // Throws while setup is disabled, before an auth-flow row exists.
    String state = flows.beginManifest(currentPrincipal.get().userId());
    String organization = request == null ? null : request.githubOrganization;
    ManifestSubmission submission = github.manifest(base, state, organization);
    return new ManifestDto(submission.actionUrl(), submission.manifest());
  }

  @GET
  @Path("/manifest/callback")
  @Produces(MediaType.TEXT_HTML)
  public Response complete(@QueryParam("code") String code, @QueryParam("state") String state) {
    if (!flows.consumeManifest(state, currentPrincipal.get().userId())) {
      throw new BadRequestException("GitHub App registration state is invalid or expired");
    }
    github.completeManifest(code);
    return Response.seeOther(publicUrl.resolve("console/integrations?github=registered")).build();
  }

  public static class ManifestRequest {
    @JsonProperty("githubOrganization")
    public String githubOrganization;
  }

  public record ManifestDto(
      @JsonProperty("actionUrl") String actionUrl, @JsonProperty("manifest") String manifest) {}

  public record GitHubAppDto(
      @JsonProperty("setupEnabled") boolean setupEnabled,
      @JsonProperty("configured") boolean configured,
      @JsonProperty("appId") Long appId,
      @JsonProperty("slug") String slug,
      @JsonProperty("owner") String owner,
      @JsonProperty("htmlUrl") String htmlUrl,
      @JsonProperty("installationUrl") String installationUrl,
      @JsonProperty("version") Integer version) {

    static GitHubAppDto of(VcsPublicUrl publicUrl, GitHubAppManager github) {
      boolean setup = publicUrl.enabled();
      return github
          .status()
          .map(
              status ->
                  new GitHubAppDto(
                      setup,
                      true,
                      status.appId(),
                      status.slug(),
                      status.owner(),
                      status.htmlUrl(),
                      status.htmlUrl() + "/installations/new",
                      status.version()))
          .orElseGet(() -> new GitHubAppDto(setup, false, null, null, null, null, null, null));
    }
  }
}
