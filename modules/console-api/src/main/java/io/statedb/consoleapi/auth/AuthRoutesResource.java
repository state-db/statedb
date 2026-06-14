package io.statedb.consoleapi.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.statedb.persistence.identity.OidcProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

  @Inject SecurityIdentity identity;

  @Inject
  @ConfigProperty(name = "state-db.oidc.default-provider")
  Optional<String> defaultProvider;

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
}
