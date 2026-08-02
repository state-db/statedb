package app.statedb.consoleapi.auth;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Console-root half of the native {@code pulumi login} bridge (the authenticated mint handler lives
 * in {@link AuthRoutesResource} at {@code /api/auth/cli-login}, where the session cookie is sent).
 *
 * <p>{@code pulumi login <backend>} starts a loopback listener, generates a nonce, and opens the
 * browser to {@code <console>/cli-login?cliSessionPort=&cliSessionNonce=} (console root — that path
 * is fixed by the Pulumi CLI; see {@code pkg/backend/httpstate/backend.go}). Because the session
 * cookie is scoped to {@code /api/}, this root entry point can't see it, so it just validates the
 * loopback parameters and bounces into the {@code /api/} space where the cookie is sent and
 * quarkus-oidc can challenge an anonymous caller.
 */
@Path("/")
public class CliLoginResource {

  // Pulumi's loopback entry point (console root). Public — it carries no credential and only
  // redirects into /api/ where the session cookie lives and the OIDC challenge can fire.
  @GET
  @Path("cli-login")
  public Response entry(
      @QueryParam("cliSessionPort") String port,
      @QueryParam("cliSessionNonce") String nonce,
      @QueryParam("cliSessionDescription") String description) {
    if (parsePort(port) == -1 || isBlank(nonce)) {
      return badRequest("cli-login requires a valid cliSessionPort and cliSessionNonce");
    }
    StringBuilder target =
        new StringBuilder(AuthRoutesResource.CLI_LOGIN_PATH)
            .append("?cliSessionPort=")
            .append(enc(port))
            .append("&cliSessionNonce=")
            .append(enc(nonce));
    if (!isBlank(description)) {
      target.append("&cliSessionDescription=").append(enc(description));
    }
    return Response.seeOther(URI.create(target.toString())).build();
  }

  // Where Pulumi's listener sends the browser once it has the token; the CLI, not this page, owns
  // the credential write, so this is purely a "you can close this tab" confirmation.
  @GET
  @Path("welcome/cli")
  @Produces(MediaType.TEXT_HTML)
  public Response welcome() {
    String body =
        """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="referrer" content="no-referrer" />
            <title>Logged in</title>
          </head>
          <body>
            <h1>You're logged in.</h1>
            <p>Your CLI token has been saved. You can close this tab and return to your terminal.</p>
          </body>
        </html>
        """;
    return Response.ok(body)
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  // -1 for missing / non-numeric / out-of-range; a valid TCP port otherwise. Shared with the
  // /api/auth/cli-login handler so both ends validate the loopback port identically.
  static int parsePort(String raw) {
    if (isBlank(raw)) {
      return -1;
    }
    try {
      int p = Integer.parseInt(raw.trim());
      return (p >= 1 && p <= 65535) ? p : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  static Response badRequest(String message) {
    return Response.status(Response.Status.BAD_REQUEST)
        .type(MediaType.TEXT_PLAIN)
        .entity(message)
        .build();
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
