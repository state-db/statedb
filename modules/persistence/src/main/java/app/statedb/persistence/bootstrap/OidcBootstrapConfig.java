package app.statedb.persistence.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Env-driven configuration for the boot-time OIDC provider upsert ({@link OidcProviderBootstrap}).
 * Mirrors {@link BootstrapConfig}: {@code STATE_DB_OIDC_BOOTSTRAP_NAME} is the sentinel — unset
 * means "no bootstrap provider," set means the remaining three keys are required, and partial
 * configuration fails fast before any DB work.
 *
 * <p>The provider this seeds makes a fresh deployment usable against an IdP without a manual DB
 * insert. Re-running with rotated secrets updates the existing row (env wins on conflict).
 */
public record OidcBootstrapConfig(
    String name, String issuer, String clientId, String clientSecret) {

  public OidcBootstrapConfig {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(clientSecret, "clientSecret");
  }

  public static final String PROP_NAME = "state-db.oidc.bootstrap.name";
  public static final String PROP_ISSUER = "state-db.oidc.bootstrap.issuer";
  public static final String PROP_CLIENT_ID = "state-db.oidc.bootstrap.client-id";
  public static final String PROP_CLIENT_SECRET = "state-db.oidc.bootstrap.client-secret";

  public static final String ENV_NAME = "STATE_DB_OIDC_BOOTSTRAP_NAME";

  private static final Map<String, String> ENV_FOR =
      Map.of(
          PROP_ISSUER, "STATE_DB_OIDC_BOOTSTRAP_ISSUER",
          PROP_CLIENT_ID, "STATE_DB_OIDC_BOOTSTRAP_CLIENT_ID",
          PROP_CLIENT_SECRET, "STATE_DB_OIDC_BOOTSTRAP_CLIENT_SECRET");

  public static Optional<OidcBootstrapConfig> fromEnv() {
    var config = ConfigProvider.getConfig();
    return fromLookup(key -> config.getOptionalValue(key, String.class).orElse(null));
  }

  /** Test seam: supply property values directly. Keys are the {@code PROP_*} constants. */
  public static Optional<OidcBootstrapConfig> fromMap(Map<String, String> values) {
    return fromLookup(values::get);
  }

  private static Optional<OidcBootstrapConfig> fromLookup(UnaryOperator<String> lookup) {
    String name = trimToNull(lookup.apply(PROP_NAME));
    if (name == null) {
      return Optional.empty();
    }

    List<String> missing = new ArrayList<>();
    String issuer = required(lookup, PROP_ISSUER, missing);
    String clientId = required(lookup, PROP_CLIENT_ID, missing);
    String clientSecret = required(lookup, PROP_CLIENT_SECRET, missing);

    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "OIDC bootstrap configuration is partial; "
              + ENV_NAME
              + " is set but the following env vars are missing or blank: "
              + String.join(", ", missing));
    }
    return Optional.of(new OidcBootstrapConfig(name, issuer, clientId, clientSecret));
  }

  private static String required(
      UnaryOperator<String> lookup, String propKey, List<String> missing) {
    String value = trimToNull(lookup.apply(propKey));
    if (value == null) {
      missing.add(ENV_FOR.get(propKey));
    }
    return value;
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
