package app.statedb.consoleapi.vcs;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.auth.RawApiTokens;
import app.statedb.persistence.authz.Authorize;
import app.statedb.persistence.authz.AuthorizedResource;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.authz.OrgEntityLoader;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.vcs.GitHubAppManager;
import app.statedb.persistence.vcs.VcsAppRegistry;
import app.statedb.persistence.vcs.VcsAuthFlow.Purpose;
import app.statedb.persistence.vcs.VcsAuthFlows;
import app.statedb.persistence.vcs.VcsConnection;
import app.statedb.persistence.vcs.VcsPublicUrl;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Org owners connect only through a user-verified GitHub round trip. */
@Path("/api/orgs/{org}/vcs")
@SessionPrincipal
@Authorize(action = "vcs:Manage", loader = OrgEntityLoader.class)
@Produces(MediaType.APPLICATION_JSON)
public class VcsConnectionsResource {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(VcsConnectionsResource.class);

  @Inject AuthorizedResource authorized;
  @Inject CurrentPrincipal principal;
  @Inject GitHubAppManager github;
  @Inject VcsAppRegistry registry;
  @Inject VcsAuthFlows flows;
  @Inject VcsPublicUrl publicUrl;

  @GET
  @Path("/connections")
  public List<ConnectionDto> connections() {
    return VcsConnection.<VcsConnection>list("orgId", org().id).stream()
        .map(
            connection -> {
              if (!connection.enabled) return ConnectionDto.from(connection, null, null);
              try {
                return ConnectionDto.from(
                    connection, github.verifyInstallation(connection.installationId), null);
              } catch (RuntimeException failure) {
                return ConnectionDto.from(
                    connection,
                    null,
                    "Could not verify GitHub access. Reconnect or check the App installation.");
              }
            })
        .toList();
  }

  @GET
  @Path("/app")
  public GitHubIntegrationResource.GitHubAppDto app() {
    return GitHubIntegrationResource.GitHubAppDto.of(publicUrl, github);
  }

  @POST
  @Path("/connect")
  public RedirectDto begin() {
    publicUrl.base(); // Throws while setup is disabled, before an auth-flow row exists.
    int version =
        github
            .status()
            .orElseThrow(() -> new RequestValidationException("Create the GitHub App first"))
            .version();
    String state = flows.beginInstall(principal.get().userId(), org().id, version);
    return new RedirectDto(github.installationUrl(state).toString());
  }

  @GET
  @Path("/setup")
  public Response installed(
      @QueryParam("state") String state, @QueryParam("installation_id") Long installationId) {
    var flow = flows.consume(state, principal.get().userId(), org().id, Purpose.install);
    if (installationId == null || installationId <= 0) return result("installation-pending");
    if (github.status().orElseThrow().version() != flow.appVersion()) return result("restart");
    String verifier = RawApiTokens.generate();
    String oauthState =
        flows.beginOAuth(
            principal.get().userId(), org().id, flow.appVersion(), installationId, verifier);
    return redirect(
        github.authorizationUrl(
            publicUrl.resolve("api/vcs/github/callback"), oauthState, verifier));
  }

  @GET
  @Path("/callback")
  public Response complete(@QueryParam("state") String state, @QueryParam("code") String code) {
    var flow = flows.consume(state, principal.get().userId(), org().id, Purpose.oauth);
    if (code == null || code.isBlank()) return result("denied");
    try {
      var installation =
          github.completeConnection(
              code,
              publicUrl.resolve("api/vcs/github/callback"),
              flow.codeVerifier(),
              flow.installationId(),
              flow.appVersion());
      registry.connectGitHub(org().id, installation.id(), flow.appVersion());
      return result("connected");
    } catch (RuntimeException failure) {
      LOG.warnf(
          "GitHub connection verification failed org_id=%s user_id=%s installation_id=%s"
              + " failure_type=%s",
          org().id,
          principal.get().userId(),
          flow.installationId(),
          failure.getClass().getSimpleName());
      // Do not echo OAuth responses, codes, or tokens into a browser URL or log.
      return result("verification-failed");
    }
  }

  @DELETE
  @Path("/connections")
  public Response disconnect() {
    registry.disconnectGitHub(org().id);
    return Response.noContent().build();
  }

  private Organization org() {
    return authorized.subject(Organization.class);
  }

  private Response result(String outcome) {
    return redirect(
        UriBuilder.fromUri(publicUrl.resolve("console/integrations"))
            .queryParam("github", outcome)
            .queryParam("org", org().name)
            .build());
  }

  private static Response redirect(URI location) {
    return Response.seeOther(location)
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  public record RedirectDto(@JsonProperty("url") String url) {}

  public record ConnectionDto(
      @JsonProperty("id") UUID id,
      @JsonProperty("kind") String kind,
      @JsonProperty("host") String host,
      @JsonProperty("installationId") Long installationId,
      @JsonProperty("enabled") boolean enabled,
      @JsonProperty("version") int version,
      @JsonProperty("accountLogin") String accountLogin,
      @JsonProperty("repositorySelection") String repositorySelection,
      @JsonProperty("managementUrl") String managementUrl,
      @JsonProperty("error") String error) {
    static ConnectionDto from(
        VcsConnection c, GitHubAppManager.Installation installation, String error) {
      return new ConnectionDto(
          c.id,
          c.kind,
          c.host,
          c.installationId,
          c.enabled,
          c.version,
          installation == null ? null : installation.accountLogin(),
          installation == null ? null : installation.repositorySelection(),
          installation == null ? null : installation.managementUrl(),
          error);
    }
  }
}
