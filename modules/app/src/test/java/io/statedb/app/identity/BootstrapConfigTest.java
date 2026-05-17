package io.statedb.app.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.statedb.persistence.identity.BootstrapConfig;
import java.util.HashMap;
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
  void allKeysPresentParses() {
    Optional<BootstrapConfig> parsed = BootstrapConfig.fromMap(fullValues());
    assertTrue(parsed.isPresent());
    BootstrapConfig cfg = parsed.get();
    assertEquals("acme", cfg.orgName());
    assertEquals("Acme Inc", cfg.orgDisplayName());
    assertEquals("ops@acme.example", cfg.adminEmail());
    assertEquals("Acme Ops", cfg.adminDisplayName());
    assertEquals("sha256-of-token", cfg.adminTokenHash());
  }

  @Test
  void emptyMapYieldsEmpty() {
    assertTrue(BootstrapConfig.fromMap(Map.of()).isEmpty());
  }

  @Test
  void blankOrgNameYieldsEmpty() {
    assertTrue(BootstrapConfig.fromMap(Map.of(BootstrapConfig.PROP_ORG_NAME, "   ")).isEmpty());
  }

  @Test
  void orgNameSetButOthersMissingThrows() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> BootstrapConfig.fromMap(Map.of(BootstrapConfig.PROP_ORG_NAME, "acme")));
    String msg = ex.getMessage();
    assertTrue(msg.contains("STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME"), msg);
    assertTrue(msg.contains("STATE_DB_BOOTSTRAP_ADMIN_EMAIL"), msg);
    assertTrue(msg.contains("STATE_DB_BOOTSTRAP_ADMIN_DISPLAY_NAME"), msg);
    assertTrue(msg.contains("STATE_DB_BOOTSTRAP_ADMIN_TOKEN_HASH"), msg);
  }

  @Test
  void singleBlankFieldNamesOnlyThatEnvVar() {
    Map<String, String> values = fullValues();
    values.put(BootstrapConfig.PROP_ADMIN_TOKEN_HASH, "");
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> BootstrapConfig.fromMap(values));
    String msg = ex.getMessage();
    assertTrue(msg.contains("STATE_DB_BOOTSTRAP_ADMIN_TOKEN_HASH"), msg);
    assertTrue(!msg.contains("STATE_DB_BOOTSTRAP_ADMIN_EMAIL"), msg);
  }

  private static Map<String, String> fullValues() {
    Map<String, String> m = new HashMap<>();
    m.put(BootstrapConfig.PROP_ORG_NAME, "acme");
    m.put(BootstrapConfig.PROP_ORG_DISPLAY_NAME, "Acme Inc");
    m.put(BootstrapConfig.PROP_ADMIN_EMAIL, "ops@acme.example");
    m.put(BootstrapConfig.PROP_ADMIN_DISPLAY_NAME, "Acme Ops");
    m.put(BootstrapConfig.PROP_ADMIN_TOKEN_HASH, "sha256-of-token");
    return m;
  }
}
