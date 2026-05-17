package io.statedb.persistence.bootstrap;

import java.util.ArrayList;
import java.util.List;
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
 * {@link Optional#empty()} and bootstrap is considered opted-out. When set, the four remaining keys
 * are required — partial configuration throws {@link IllegalStateException} <em>before</em> any
 * database work runs, so the app fails fast instead of coming up half-bootstrapped.
 *
 * <p>Lookups use MicroProfile property keys; the env-var spelling is the operator-facing form (env
 * vars auto-map to property keys by uppercasing and replacing punctuation with underscores). Error
 * messages report env-var names because that's what operators set.
 */
public record BootstrapConfig(
    String orgName,
    String orgDisplayName,
    String adminEmail,
    String adminDisplayName,
    String adminTokenHash) {

  public BootstrapConfig {
    Objects.requireNonNull(orgName, "orgName");
    Objects.requireNonNull(orgDisplayName, "orgDisplayName");
    Objects.requireNonNull(adminEmail, "adminEmail");
    Objects.requireNonNull(adminDisplayName, "adminDisplayName");
    Objects.requireNonNull(adminTokenHash, "adminTokenHash");
  }

  public static final String PROP_ORG_NAME = "state-db.bootstrap.org-name";
  public static final String PROP_ORG_DISPLAY_NAME = "state-db.bootstrap.org-display-name";
  public static final String PROP_ADMIN_EMAIL = "state-db.bootstrap.admin.email";
  public static final String PROP_ADMIN_DISPLAY_NAME = "state-db.bootstrap.admin.display-name";
  public static final String PROP_ADMIN_TOKEN_HASH = "state-db.bootstrap.admin.token-hash";

  public static final String ENV_ORG_NAME = "STATE_DB_BOOTSTRAP_ORG_NAME";

  private static final Map<String, String> ENV_FOR =
      Map.of(
          PROP_ORG_DISPLAY_NAME, "STATE_DB_BOOTSTRAP_ORG_DISPLAY_NAME",
          PROP_ADMIN_EMAIL, "STATE_DB_BOOTSTRAP_ADMIN_EMAIL",
          PROP_ADMIN_DISPLAY_NAME, "STATE_DB_BOOTSTRAP_ADMIN_DISPLAY_NAME",
          PROP_ADMIN_TOKEN_HASH, "STATE_DB_BOOTSTRAP_ADMIN_TOKEN_HASH");

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

    List<String> missing = new ArrayList<>();
    String orgDisplayName = required(lookup, PROP_ORG_DISPLAY_NAME, missing);
    String adminEmail = required(lookup, PROP_ADMIN_EMAIL, missing);
    String adminDisplayName = required(lookup, PROP_ADMIN_DISPLAY_NAME, missing);
    String adminTokenHash = required(lookup, PROP_ADMIN_TOKEN_HASH, missing);

    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Bootstrap configuration is partial; "
              + ENV_ORG_NAME
              + " is set but the following env vars are missing or blank: "
              + String.join(", ", missing));
    }
    return Optional.of(
        new BootstrapConfig(orgName, orgDisplayName, adminEmail, adminDisplayName, adminTokenHash));
  }

  private static String required(
      UnaryOperator<String> lookup, String propKey, List<String> missing) {
    String value = trimToNull(lookup.apply(propKey));
    if (value == null) missing.add(ENV_FOR.get(propKey));
    return value;
  }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
