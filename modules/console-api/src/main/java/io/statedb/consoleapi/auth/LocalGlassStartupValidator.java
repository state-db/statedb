package io.statedb.consoleapi.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * When break-glass is configured, fails fast at boot if the session encryption secret is missing —
 * the form-auth cookie is encrypted with it ({@code quarkus.http.auth.session.encryption-key} maps
 * to the same property), and without it Quarkus falls back to a per-boot random key that breaks
 * multi-pod deployments silently. Also leaves an operator-facing WARN that the login is live.
 */
@ApplicationScoped
public class LocalGlassStartupValidator {

  private static final Logger LOG = Logger.getLogger(LocalGlassStartupValidator.class);

  @Inject LocalGlass glass;

  @Inject SessionEncryptionSecret sessionSecret;

  void onStart(@Observes StartupEvent event) {
    if (!glass.passwordConfigured()) {
      return;
    }
    if (!sessionSecret.isConfigured()) {
      sessionSecret.value(); // throws with the operator-facing message
    }
    LOG.warnf(
        "Local break-glass console login is configured (%s); POST /api/auth/local is honored while"
            + " no OIDC provider is enabled",
        glass.allowRemote() ? "remote access allowed" : "loopback only");
  }
}
