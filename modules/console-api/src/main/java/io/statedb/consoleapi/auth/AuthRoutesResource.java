package io.statedb.consoleapi.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.statedb.consoleapi.auth.CliLoginApprovalSigner.CliLoginApproval;
import io.statedb.persistence.auth.CliTokenMinter;
import io.statedb.persistence.auth.CliTokenMinter.MintedCliToken;
import io.statedb.persistence.identity.OidcProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Console authentication routes that are not owned by {@code quarkus-oidc} itself.
 *
 * <p>The redirect to the IdP can only be issued by the security layer. The HTTP permissions in
 * {@code application.yml} leave the provider-validation route public and require authentication for
 * the flow route, so an unknown provider can be rejected before Quarkus starts an OIDC challenge.
 *
 * <ul>
 *   <li>{@code GET /api/auth/login} uses the configured default provider. This is the entrypoint
 *       the SPA uses for the common single-provider deployment.
 *   <li>{@code GET /api/auth/login/{provider}} is public and validates the provider: unknown or
 *       disabled → 404; already signed in → bounce to the SPA root; otherwise redirect to the
 *       {@code flow} route below. Provider-specific SSO links use this route.
 *   <li>{@code GET /api/auth/flow/{provider}} requires authentication: for an anonymous caller the
 *       security layer hands off to quarkus-oidc, which redirects to the tenant's IdP. After the
 *       callback establishes the session, quarkus-oidc restores this original URL and the handler
 *       bounces the browser to the SPA.
 * </ul>
 *
 * <p>The IdP callback ({@code /api/auth/callback/{provider}}) is owned entirely by quarkus-oidc. An
 * authenticated HTTP permission covers that path so the mechanism processes code/state even with
 * proactive auth disabled. {@link OidcUserProvisioner} persists the user inside the successful
 * code-flow completion, before Quarkus creates the session cookie.
 */
@Path("/api/auth")
public class AuthRoutesResource {

  static final String CLI_LOGIN_PATH = "/api/auth/cli-login";

  public record AuthProvider(String name, String displayName) {}

  public record AuthProviders(List<AuthProvider> providers, boolean breakGlass) {}

  @Inject SecurityIdentity identity;

  @Inject LocalGlass glass;

  @Inject CliTokenMinter minter;

  @Inject CliLoginApprovalSigner signer;

  @Inject
  @ConfigProperty(name = "state-db.oidc.default-provider")
  Optional<String> defaultProvider;

  @GET
  @Path("/login")
  public Response login(@QueryParam("returnTo") String returnTo) {
    return login(
        defaultProvider.orElseThrow(
            () ->
                new ServiceUnavailableException(
                    "STATE_DB_OIDC_DEFAULT_PROVIDER is not configured")),
        returnTo);
  }

  @GET
  @Path("/login/{provider}")
  public Response login(
      @PathParam("provider") String provider, @QueryParam("returnTo") String returnTo) {
    String target = consoleReturnTo(returnTo);
    if (!identity.isAnonymous()) {
      return Response.seeOther(URI.create(target)).build();
    }
    if (OidcProvider.findEnabled(provider).isEmpty()) {
      throw new NotFoundException();
    }
    return Response.seeOther(
            URI.create(
                "/api/auth/flow/"
                    + provider
                    + "?returnTo="
                    + URLEncoder.encode(target, StandardCharsets.UTF_8)))
        .build();
  }

  @GET
  @Path("/providers")
  @Blocking
  @Produces(MediaType.APPLICATION_JSON)
  public AuthProviders providers() {
    List<AuthProvider> providers =
        OidcProvider.<OidcProvider>list("enabled = true order by name").stream()
            .map(provider -> new AuthProvider(provider.name, provider.name))
            .toList();
    // breakGlass tells the sign-in screen whether the /api/auth/local form has any chance of
    // succeeding; the loopback/provider gate is still enforced server-side on the POST.
    return new AuthProviders(providers, providers.isEmpty() && glass.passwordConfigured());
  }

  /**
   * Mints a short-lived CLI token from the current console session and reveals it once to the SPA.
   *
   * <p>No CSRF token is required: the Lax session cookie is not sent on cross-site POSTs.
   */
  @POST
  @Path("/cli-mint")
  @Blocking
  public Response cliMint() {
    JsonWebToken jwt = sessionJwt();
    MintedCliToken minted = minter.mint(jwt.getIssuer(), jwt.getSubject(), "CLI token");
    String target =
        "/console/account/tokens?minted="
            + minted.id()
            + "#token="
            + CliLoginResource.enc(minted.rawToken());
    return Response.status(Response.Status.FOUND)
        .location(URI.create(target))
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  @GET
  @Path("/flow/{provider}")
  public Response flow(
      @PathParam("provider") String provider, @QueryParam("returnTo") String returnTo) {
    return Response.seeOther(URI.create(consoleReturnTo(returnTo))).build();
  }

  /**
   * Authenticated half of the native {@code pulumi login} flow (see {@link CliLoginResource}). The
   * {@code /api/*} HTTP permission plus {@link ProviderTenantConfigResolver}'s self-start establish
   * a console session first — an anonymous hit is challenged into SSO and restored here. With a
   * session this GET renders a human approval page and does not mint: the side effect happens only
   * after the user clicks Allow, via {@link #approveCliLogin}. The redirect host is hard-coded to
   * {@code 127.0.0.1} (only the port varies), so a crafted link cannot exfiltrate the token to
   * another host; the nonce is echoed back for the CLI listener to verify.
   */
  @GET
  @Path("/cli-login")
  @Blocking
  @Produces(MediaType.TEXT_HTML)
  public Response cliLogin(
      @QueryParam("cliSessionPort") String port,
      @QueryParam("cliSessionNonce") String nonce,
      @QueryParam("cliSessionDescription") String description) {
    int p = CliLoginResource.parsePort(port);
    if (p == -1 || CliLoginResource.isBlank(nonce)) {
      return CliLoginResource.badRequest(
          "cli-login requires a valid cliSessionPort and cliSessionNonce");
    }
    JsonWebToken jwt = sessionJwt();

    String signedRequest =
        signer.sign(
            CliLoginApproval.issue(p, nonce, description, jwt.getIssuer(), jwt.getSubject()));
    String signedInAs =
        Optional.ofNullable(jwt.getClaim("email")).map(Object::toString).orElse(jwt.getSubject());
    String clientLabel = CliLoginResource.isBlank(description) ? "Pulumi CLI" : description.strip();
    return credentialPage(CliLoginPages.approvalPage(signedInAs, clientLabel, p, signedRequest))
        .build();
  }

  @POST
  @Path("/cli-login/approve")
  @Blocking
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response approveCliLogin(
      @FormParam("request") String signedRequest, @FormParam("decision") String decision) {
    JsonWebToken jwt = sessionJwt();
    CliLoginApproval approval = signer.consume(signedRequest);
    if (!approval.iss().equals(jwt.getIssuer()) || !approval.sub().equals(jwt.getSubject())) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    if (!"allow".equals(decision)) {
      return credentialPage(CliLoginPages.cancelledPage()).type(MediaType.TEXT_HTML).build();
    }

    MintedCliToken minted = minter.mint(jwt.getIssuer(), jwt.getSubject(), approval.description());

    String loopback =
        "http://127.0.0.1:"
            + approval.port()
            + "/?accessToken="
            + CliLoginResource.enc(minted.rawToken())
            + "&nonce="
            + CliLoginResource.enc(approval.nonce());
    return Response.status(Response.Status.FOUND).location(URI.create(loopback)).build();
  }

  private JsonWebToken sessionJwt() {
    if (identity.getPrincipal() instanceof JsonWebToken jwt) {
      return jwt;
    }
    throw new NotAuthorizedException("OIDC");
  }

  private static String consoleReturnTo(String value) {
    if (value == null || value.isBlank() || !value.startsWith("/") || value.startsWith("//")) {
      return "/console";
    }
    try {
      // Values that pass the prefix guard can still be URI-invalid (spaces, control characters);
      // reject them here rather than letting Response.seeOther's URI.create throw a 500.
      new URI(value);
    } catch (URISyntaxException e) {
      return "/console";
    }
    return value;
  }

  // Credential-issuing pages: must not be cached, leak a referrer, or be framable — a remote site
  // could clickjack the "Allow access" button and deliver a token to an attacker-chosen loopback
  // port.
  private static Response.ResponseBuilder credentialPage(String body) {
    return Response.ok(body)
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Frame-Options", "DENY")
        .header("Content-Security-Policy", "frame-ancestors 'none'");
  }
}
