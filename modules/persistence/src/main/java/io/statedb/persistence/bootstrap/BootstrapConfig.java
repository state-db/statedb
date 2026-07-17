package io.statedb.persistence.bootstrap;

import io.statedb.persistence.NameGrammar;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Env-driven configuration for {@link BootstrapProvisioner}. Resolved once at startup and never
 * mutated.
 *
 * <p>{@code STATE_DB_BOOTSTRAP_ORG_NAME} is the sentinel: when unset, {@link #fromEnv()} returns
 * {@link Optional#empty()} and bootstrap is considered opted-out. {@code
 * STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME} is optional and defaults to the org name.
 *
 * <p>Lookups use MicroProfile property keys; the env-var spelling is the operator-facing form (env
 * vars auto-map to property keys by uppercasing and replacing punctuation with underscores).
 */
public record BootstrapConfig(String orgName, String orgDisplayName) {

  public BootstrapConfig {
    Objects.requireNonNull(orgName, "orgName");
    if (!NameGrammar.isValid(orgName)) {
      throw new IllegalStateException(
          ENV_ORG_NAME + " must be 1-100 characters of [A-Za-z0-9._-], got '" + orgName + "'");
    }
    Objects.requireNonNull(orgDisplayName, "orgDisplayName");
  }

  public static final String PROP_ORG_NAME = "state-db.bootstrap.org-name";
  public static final String PROP_ORG_DISPLAY_NAME = "state-db.bootstrap.org-display-name";

  public static final String ENV_ORG_NAME = "STATE_DB_BOOTSTRAP_ORG_NAME";

  public static Optional<BootstrapConfig> fromEnv() {
    var config = ConfigProvider.getConfig();
    return fromLookup(key -> config.getOptionalValue(key, String.class).orElse(null));
  }

  /** Test seam: supply property values directly. Keys are the {@code PROP_*} constants. */
  public static Optional<BootstrapConfig> fromMap(Map<String, String> values) {
    return fromLookup(values::get);
  }

  private static Optional<BootstrapConfig> fromLookup(UnaryOperator<String> lookup) {
    String orgName = trimToNull(lookup.apply(PROP_ORG_NAME));
    if (orgName == null) return Optional.empty();

    String orgDisplayName = trimToNull(lookup.apply(PROP_ORG_DISPLAY_NAME));
    return Optional.of(
        new BootstrapConfig(orgName, orgDisplayName == null ? orgName : orgDisplayName));
  }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
