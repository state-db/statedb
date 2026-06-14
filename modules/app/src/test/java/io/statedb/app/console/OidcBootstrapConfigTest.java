package io.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.statedb.persistence.bootstrap.OidcBootstrapConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link OidcBootstrapConfig} sentinel + fail-fast-on-partial behavior. */
class OidcBootstrapConfigTest {

  @Test
  void absentNameOptsOut() {
    assertTrue(OidcBootstrapConfig.fromMap(Map.of()).isEmpty());
  }

  @Test
  void partialConfigFailsFastNamingMissingEnvVars() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> OidcBootstrapConfig.fromMap(Map.of(OidcBootstrapConfig.PROP_NAME, "google")));
    assertTrue(ex.getMessage().contains("STATE_DB_OIDC_BOOTSTRAP_ISSUER"), ex.getMessage());
    assertTrue(ex.getMessage().contains("STATE_DB_OIDC_BOOTSTRAP_CLIENT_ID"), ex.getMessage());
    assertTrue(ex.getMessage().contains("STATE_DB_OIDC_BOOTSTRAP_CLIENT_SECRET"), ex.getMessage());
  }

  @Test
  void completeConfigParses() {
    OidcBootstrapConfig config =
        OidcBootstrapConfig.fromMap(
                Map.of(
                    OidcBootstrapConfig.PROP_NAME, "google",
                    OidcBootstrapConfig.PROP_ISSUER, "https://accounts.google.com",
                    OidcBootstrapConfig.PROP_CLIENT_ID, "client-123",
                    OidcBootstrapConfig.PROP_CLIENT_SECRET, "secret-xyz"))
            .orElseThrow();

    assertEquals("google", config.name());
    assertEquals("https://accounts.google.com", config.issuer());
    assertEquals("client-123", config.clientId());
    assertEquals("secret-xyz", config.clientSecret());
  }
}
