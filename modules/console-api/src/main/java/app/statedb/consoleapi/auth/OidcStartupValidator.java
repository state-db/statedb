package app.statedb.consoleapi.auth;

import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fails fast at boot when console OIDC is in play but the session encryption secret is missing or
 * too short. Without this, a misconfigured deployment starts green and only fails at the first
 * login attempt — the worst time to find out. "In play" means a default provider is configured or
 * an enabled {@code oidc_providers} row exists; a provider enabled later at runtime still hits the
 * lazy check in {@link SessionEncryptionSecret#value()}.
 */
@ApplicationScoped
public class OidcStartupValidator {

  @Inject SessionEncryptionSecret sessionSecret;

  @Inject
  @ConfigProperty(name = "state-db.oidc.default-provider")
  Optional<String> defaultProvider;

  void onStart(@Observes StartupEvent event) {
    if (sessionSecret.isConfigured()) {
      return;
    }
    boolean oidcInPlay =
        defaultProvider.isPresent()
            || QuarkusTransaction.requiringNew()
                .call(() -> OidcProvider.count("enabled = true") > 0);
    if (oidcInPlay) {
      sessionSecret.value(); // throws with the operator-facing message
    }
  }
}
