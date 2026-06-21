package io.statedb.consoleapi.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
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
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

  @Inject SecurityIdentity identity;

  @Inject CliTokenMinter minter;

  @Inject
  @ConfigProperty(name = "state-db.oidc.default-provider")
  Optional<String> defaultProvider;

  @Inject
  @ConfigProperty(name = "state-db.session.encryption-secret")
  Optional<String> sessionEncryptionSecret;

  @GET
  @Path("/login")
  public Response login() {
    return login(
        defaultProvider.orElseThrow(
            () ->
                new ServiceUnavailableException(
                    "STATE_DB_OIDC_DEFAULT_PROVIDER is not configured")));
  }

  @GET
  @Path("/login/{provider}")
  public Response login(@PathParam("provider") String provider) {
    if (!identity.isAnonymous()) {
      return Response.seeOther(URI.create("/")).build();
    }
    if (OidcProvider.findEnabled(provider).isEmpty()) {
      throw new NotFoundException();
    }
    return Response.seeOther(URI.create("/api/auth/flow/" + provider)).build();
  }

  @GET
  @Path("/flow/{provider}")
  public Response flow(@PathParam("provider") String provider) {
    return Response.seeOther(URI.create("/")).build();
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
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      throw new NotAuthorizedException("OIDC");
    }

    String request =
        sign(
            new CliLoginApproval(
                p,
                nonce,
                description,
                jwt.getIssuer(),
                jwt.getSubject(),
                Instant.now().plusSeconds(600).getEpochSecond()));
    return Response.ok(approvalPage(jwt, p, nonce, description, request))
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        // Credential-issuing page: must not be framable, or a remote site could clickjack the
        // "Allow access" button and deliver a token to an attacker-chosen loopback port.
        .header("X-Frame-Options", "DENY")
        .header("Content-Security-Policy", "frame-ancestors 'none'")
        .build();
  }

  @POST
  @Path("/cli-login/approve")
  @Blocking
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response approveCliLogin(
      @FormParam("request") String signedRequest, @FormParam("decision") String decision) {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      throw new NotAuthorizedException("OIDC");
    }
    CliLoginApproval approval = verify(signedRequest);
    if (!approval.iss().equals(jwt.getIssuer()) || !approval.sub().equals(jwt.getSubject())) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    if (!"allow".equals(decision)) {
      return Response.ok(cancelledPage())
          .type(MediaType.TEXT_HTML)
          .header("Cache-Control", "no-store")
          .header("Referrer-Policy", "no-referrer")
          .header("X-Frame-Options", "DENY")
          .header("Content-Security-Policy", "frame-ancestors 'none'")
          .build();
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

  private String approvalPage(
      JsonWebToken jwt, int port, String nonce, String description, String signedRequest) {
    String label = CliLoginResource.isBlank(description) ? "Pulumi CLI" : html(description.strip());
    return """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="referrer" content="no-referrer" />
            <title>Allow CLI access</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 3rem auto; max-width: 40rem; line-height: 1.45; }
              dl { display: grid; grid-template-columns: 9rem 1fr; gap: .5rem 1rem; }
              dt { color: #555; }
              dd { margin: 0; }
              button { padding: .65rem 1rem; margin-right: .5rem; }
            </style>
          </head>
          <body>
            <h1>Allow CLI access?</h1>
            <p>Pulumi CLI is requesting a 24-hour state-db token for your account.</p>
            <dl>
              <dt>Signed in as</dt><dd>%s</dd>
              <dt>Client</dt><dd>%s</dd>
              <dt>Access</dt><dd>Organizations you currently belong to</dd>
              <dt>Loopback</dt><dd>127.0.0.1:%d</dd>
            </dl>
            <form method="post" action="/api/auth/cli-login/approve">
              <input type="hidden" name="request" value="%s" />
              <button type="submit" name="decision" value="allow">Allow access</button>
              <button type="submit" name="decision" value="cancel">Cancel</button>
            </form>
          </body>
        </html>
        """
        .formatted(
            html(
                Optional.ofNullable(jwt.getClaim("email"))
                    .map(Object::toString)
                    .orElse(jwt.getSubject())),
            label,
            port,
            html(signedRequest));
  }

  private static String cancelledPage() {
    return """
        <!doctype html>
        <html lang="en">
          <head><meta charset="utf-8" /><title>CLI login cancelled</title></head>
          <body><h1>CLI login cancelled.</h1><p>No token was created.</p></body>
        </html>
        """;
  }

  private String sign(CliLoginApproval approval) {
    String payload = b64(approval.encode());
    return payload + "." + b64(hmac(payload));
  }

  private CliLoginApproval verify(String signedRequest) {
    if (signedRequest == null || signedRequest.isBlank()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    int dot = signedRequest.indexOf('.');
    if (dot <= 0 || dot != signedRequest.lastIndexOf('.')) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    String payload = signedRequest.substring(0, dot);
    byte[] actual = b64decode(signedRequest.substring(dot + 1));
    byte[] expected = hmac(payload);
    if (!MessageDigest.isEqual(actual, expected)) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    CliLoginApproval approval =
        CliLoginApproval.decode(new String(b64decode(payload), StandardCharsets.UTF_8));
    if (approval.expiresAtEpochSecond() < Instant.now().getEpochSecond()) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    return approval;
  }

  private byte[] hmac(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private String signingSecret() {
    return sessionEncryptionSecret
        .filter(s -> s.length() >= 32)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "STATE_DB_SESSION_ENCRYPTION_SECRET must contain at least 32 characters"));
  }

  private static String b64(String s) {
    return b64(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] b64decode(String s) {
    try {
      return Base64.getUrlDecoder().decode(s);
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
  }

  private static String html(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private record CliLoginApproval(
      int port,
      String nonce,
      String description,
      String iss,
      String sub,
      long expiresAtEpochSecond) {
    String encode() {
      return String.join(
          "\n",
          Integer.toString(port),
          b64(nonce),
          b64(description == null ? "" : description),
          b64(iss),
          b64(sub),
          Long.toString(expiresAtEpochSecond));
    }

    static CliLoginApproval decode(String encoded) {
      String[] parts = encoded.split("\n", -1);
      if (parts.length != 6) {
        throw new WebApplicationException(Response.Status.BAD_REQUEST);
      }
      int port = CliLoginResource.parsePort(parts[0]);
      if (port == -1) {
        throw new WebApplicationException(Response.Status.BAD_REQUEST);
      }
      String nonce = new String(b64decode(parts[1]), StandardCharsets.UTF_8);
      if (CliLoginResource.isBlank(nonce)) {
        throw new WebApplicationException(Response.Status.BAD_REQUEST);
      }
      try {
        return new CliLoginApproval(
            port,
            nonce,
            new String(b64decode(parts[2]), StandardCharsets.UTF_8),
            new String(b64decode(parts[3]), StandardCharsets.UTF_8),
            new String(b64decode(parts[4]), StandardCharsets.UTF_8),
            Long.parseLong(parts[5]));
      } catch (NumberFormatException e) {
        throw new WebApplicationException(Response.Status.BAD_REQUEST);
      }
    }
  }
}
