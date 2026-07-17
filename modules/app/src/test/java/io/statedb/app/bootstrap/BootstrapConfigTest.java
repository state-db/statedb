package io.statedb.app.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.statedb.persistence.bootstrap.BootstrapConfig;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-level validation of {@link BootstrapConfig#fromMap(Map)}. The fromEnv path is the same
 * resolver wired to {@link org.eclipse.microprofile.config.ConfigProvider}; fromMap proves the
 * validation rules.
 */
class BootstrapConfigTest {

  @Test
  void parsesOrgNameAndDisplayName() {
    Optional<BootstrapConfig> parsed =
        BootstrapConfig.fromMap(
            Map.of(
                BootstrapConfig.PROP_ORG_NAME, "acme",
                BootstrapConfig.PROP_ORG_DISPLAY_NAME, "Acme Inc"));
    assertTrue(parsed.isPresent());
    assertEquals("acme", parsed.get().orgName());
    assertEquals("Acme Inc", parsed.get().orgDisplayName());
  }

  @Test
  void displayNameDefaultsToOrgName() {
    BootstrapConfig cfg =
        BootstrapConfig.fromMap(Map.of(BootstrapConfig.PROP_ORG_NAME, "acme")).orElseThrow();
    assertEquals("acme", cfg.orgDisplayName());
  }

  @Test
  void unsetOrgNameOptsOut() {
    assertTrue(BootstrapConfig.fromMap(Map.of()).isEmpty());
  }

  @Test
  void blankOrgNameOptsOut() {
    assertTrue(BootstrapConfig.fromMap(Map.of(BootstrapConfig.PROP_ORG_NAME, "   ")).isEmpty());
  }

  @Test
  void invalidOrgNameFailsFast() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> BootstrapConfig.fromMap(Map.of(BootstrapConfig.PROP_ORG_NAME, "not a name!")));
    assertTrue(ex.getMessage().contains(BootstrapConfig.ENV_ORG_NAME), ex.getMessage());
  }
}
