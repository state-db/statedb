package io.statedb.consoleapi.auth;

import io.quarkus.oidc.OidcRequestContext;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.TenantConfigResolver;
import io.quarkus.oidc.runtime.OidcTenantConfig.ApplicationType;
import io.quarkus.oidc.runtime.OidcTenantConfig.Authentication.CookieSameSite;
import io.smallrye.mutiny.Uni;
import io.statedb.persistence.identity.EnabledOidcProviderLookup;
import io.statedb.persistence.identity.OidcProvider;
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

  @Inject EnabledOidcProviderLookup providerLookup;

  // Encrypts the web-app session cookie. It must be stable across replicas and at least 32
  // characters; tests provide an explicit value rather than relying on Quarkus's weak fallback.
  @Inject
  @ConfigProperty(name = "state-db.session.encryption-secret")
  Optional<String> sessionEncryptionSecret;

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

  private static String tenantId(RoutingContext context) {
    String fromCookie = context.get(TENANT_ID_ATTRIBUTE);
    if (fromCookie != null && !fromCookie.isBlank()) {
      return fromCookie;
    }
    Matcher m = AUTH_ROUTE.matcher(context.normalizedPath());
    return m.find() ? m.group(1) : null;
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
            // Request the provider's configured scopes verbatim (they include openid) so the IdP
            // returns profile/email claims for user provisioning; otherwise only openid is sent.
            .addOpenidScope(false)
            .scopes(provider.scopes())
            .end();
    String secret =
        sessionEncryptionSecret
            .filter(s -> s.length() >= 32)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "STATE_DB_SESSION_ENCRYPTION_SECRET must contain at least 32 characters"));
    return builder.tokenStateManager().encryptionSecret(secret).end().build();
  }
}
