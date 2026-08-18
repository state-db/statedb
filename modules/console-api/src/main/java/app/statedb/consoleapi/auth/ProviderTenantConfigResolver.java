package app.statedb.consoleapi.auth;

import app.statedb.persistence.identity.EnabledOidcProviderLookup;
import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.oidc.OidcRequestContext;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.TenantConfigResolver;
import io.quarkus.oidc.runtime.OidcTenantConfig.ApplicationType;
import io.quarkus.oidc.runtime.OidcTenantConfig.Authentication.CookieSameSite;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves a {@code quarkus-oidc} tenant per request from the {@code oidc_providers} table, so
 * providers are data (added/rotated at runtime) rather than static config.
 *
 * <p>The tenant id is determined in two ways:
 *
 * <ul>
 *   <li><b>Auth routes</b> — {@code /api/auth/flow/{name}} (the IdP challenge) and {@code
 *       /api/auth/callback/{name}} (the redirect-back) carry the provider name in the path; that's
 *       how a fresh login with no session yet is routed to the right tenant.
 *   <li><b>Data routes</b> — once a session exists, {@code quarkus-oidc} reads the tenant id from
 *       the session cookie and exposes it as the {@code tenant-id} routing-context attribute before
 *       calling this resolver; we honor that.
 * </ul>
 *
 * <p>Returning {@code null} means "no tenant" — anonymous. With the default tenant disabled, an
 * unauthenticated data request gets the security layer's 401 rather than being redirected to an
 * arbitrary IdP. Each tenant runs in {@code web-app} (BFF) mode with the session cookie scoped to
 * {@code /api/} so it never reaches {@code /pulumi/api/*}.
 */
@ApplicationScoped
public class ProviderTenantConfigResolver implements TenantConfigResolver {

  // quarkus-oidc sets this routing-context attribute from the session cookie before resolution.
  private static final String TENANT_ID_ATTRIBUTE = "tenant-id";

  // The IdP challenge fires on /flow and the redirect-back lands on /callback; both carry the
  // provider name in the path. (/login needs no tenant — it just redirects to /flow.)
  private static final Pattern AUTH_ROUTE = Pattern.compile("^/api/auth/(?:flow|callback)/([^/]+)");

  // The `pulumi login` browser flow lands here as a top-level navigation, so an unauthenticated
  // hit should challenge the user to sign in — not 401 like an SPA fetch. Resolving the default
  // provider here lets quarkus-oidc start the login and restore the cli-login URL after the
  // callback, at which point the handler mints and redirects to the CLI's loopback listener.
  private static final String CLI_LOGIN_PATH = "/api/auth/cli-login";

  @Inject EnabledOidcProviderLookup providerLookup;

  @Inject
  @ConfigProperty(name = "statedb.oidc.default-provider")
  Optional<String> defaultProvider;

  // Encrypts the web-app session cookie. It must be stable across replicas; tests provide an
  // explicit value rather than relying on Quarkus's weak fallback.
  @Inject SessionEncryptionSecret sessionEncryptionSecret;

  @Override
  public Uni<OidcTenantConfig> resolve(
      RoutingContext context, OidcRequestContext<OidcTenantConfig> requestContext) {
    String tenantId = tenantId(context);
    if (tenantId == null) {
      return Uni.createFrom().nullItem();
    }
    // TenantConfigResolver is a reactive OIDC hook, unlike a synchronous REST endpoint. Keep the
    // blocking Hibernate ORM query off the event loop.
    return requestContext.runBlocking(
        () -> providerLookup.find(tenantId).map(this::toTenantConfig).orElse(null));
  }

  private String tenantId(RoutingContext context) {
    String fromCookie = context.get(TENANT_ID_ATTRIBUTE);
    if (fromCookie != null && !fromCookie.isBlank()) {
      return fromCookie;
    }
    String path = context.normalizedPath();
    Matcher m = AUTH_ROUTE.matcher(path);
    if (m.find()) {
      return m.group(1);
    }
    // No session and a top-level cli-login navigation: challenge via the default provider so the
    // user can sign in, rather than returning the SPA-style 401 that a fetch would expect.
    if (path.startsWith(CLI_LOGIN_PATH) && defaultProvider.isPresent()) {
      return defaultProvider.get();
    }
    return null;
  }

  private OidcTenantConfig toTenantConfig(OidcProvider.ConnectionConfig provider) {
    var builder =
        OidcTenantConfig.authServerUrl(provider.issuerUrl())
            .tenantId(provider.name())
            .clientId(provider.clientId())
            .credentials(provider.clientSecret())
            .applicationType(ApplicationType.WEB_APP)
            .authentication()
            .redirectPath("/api/auth/callback/" + provider.name())
            // After the callback, return to the URL that started the flow (/api/auth/flow/{name}),
            // whose handler only bounces the authenticated browser to the SPA.
            .restorePathAfterRedirect()
            .cookiePath("/api/")
            .cookieSameSite(CookieSameSite.LAX)
            // Reuse one state cookie per tenant instead of minting a new q_auth_<uuid> cookie for
            // every challenge. Multi-tab concurrent logins aren't a requirement here, and without
            // this an abandoned/retried login (e.g. hitting the IdP page then browser Back) leaves
            // an orphaned state cookie behind each time — enough of them blow past the request
            // header size limit and break every /api/* call.
            .allowMultipleCodeFlows(false)
            // Request the provider's configured scopes verbatim (they include openid) so the IdP
            // returns profile/email claims for user provisioning; otherwise only openid is sent.
            .addOpenidScope(false)
            .scopes(provider.scopes())
            .end();
    return builder
        .tokenStateManager()
        .encryptionSecret(sessionEncryptionSecret.value())
        .end()
        .build();
  }
}
