package app.statedb.consoleapi.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Single owner of the {@code STATEDB_SESSION_ENCRYPTION_SECRET} contract: present and at least 32
 * characters. quarkus-oidc encrypts the web-app session cookie with it (it must be stable across
 * replicas), and {@link CliLoginApprovalSigner} derives its approval-signing key from it. {@link
 * OidcStartupValidator} enforces the contract at boot when OIDC is configured, so the lazy {@link
 * #value()} throw is a fallback for providers enabled at runtime, not the primary guard.
 */
@ApplicationScoped
public class SessionEncryptionSecret {

  private static final int MIN_LENGTH = 32;

  @Inject
  @ConfigProperty(name = "statedb.session.encryption-secret")
  Optional<String> raw;

  public boolean isConfigured() {
    return raw.filter(s -> s.length() >= MIN_LENGTH).isPresent();
  }

  /** The validated secret. */
  public String value() {
    return raw.filter(s -> s.length() >= MIN_LENGTH)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "STATEDB_SESSION_ENCRYPTION_SECRET must contain at least "
                        + MIN_LENGTH
                        + " characters"));
  }
}
