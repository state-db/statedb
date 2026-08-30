package app.statedb.persistence;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Arrays;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Enforces the database-upgrade contract after environment configuration is resolved. */
@Singleton
public class StateDbFlywayConfiguration implements FlywayConfigurationCustomizer {

  private static final String LOCATION = "classpath:db/migration";
  private static final String SCHEMA = "public";
  private static final String HISTORY_TABLE = "flyway_schema_history";
  private static final int LOCK_RETRY_COUNT = 300;
  private static final String INIT_SQL = "SET lock_timeout = '10s'; SET statement_timeout = '2min'";

  @Inject Instance<Flyway> flyway;

  @Override
  public void customize(FluentConfiguration configuration) {
    validateQuarkusControls();
    configuration
        .locations(LOCATION)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .outOfOrder(false)
        .mixed(false)
        .group(false)
        .ignoreMigrationPatterns(new String[0])
        .defaultSchema(SCHEMA)
        .schemas(SCHEMA)
        .table(HISTORY_TABLE)
        .createSchemas(false)
        .initSql(INIT_SQL)
        .connectRetries(10)
        .connectRetriesInterval(6)
        .lockRetryCount(LOCK_RETRY_COUNT);
  }

  void validateAtStartup(@Observes StartupEvent ignored) {
    validateQuarkusControls();
    MigrationInfo[] pending = flyway.get().info().pending();
    if (pending.length != 0) {
      throw new IllegalStateException(
          "Flyway startup completed with a pending migration: " + pending[0].getDescription());
    }
  }

  private static void validateQuarkusControls() {
    Config config = ConfigProvider.getConfig();
    require(config, "quarkus.datasource.active", true);
    require(config, "quarkus.flyway.active", true);
    require(config, "quarkus.flyway.migrate-at-start", true);
    require(config, "quarkus.flyway.clean-at-start", false);
    require(config, "quarkus.flyway.repair-at-start", false);
    require(config, "quarkus.flyway.clean-disabled", true);
    require(config, "quarkus.flyway.baseline-on-migrate", false);
    require(config, "quarkus.flyway.out-of-order", false);
    require(config, "quarkus.flyway.create-schemas", false);
    require(config, "quarkus.flyway.default-schema", SCHEMA);
    require(config, "quarkus.flyway.schemas", SCHEMA);
    require(config, "quarkus.flyway.table", HISTORY_TABLE);
    require(config, "quarkus.flyway.locations", LOCATION);
  }

  private static void require(Config config, String key, boolean required) {
    boolean actual = config.getValue(key, Boolean.class);
    if (actual != required) {
      throw unsafeOverride(key, Boolean.toString(actual), Boolean.toString(required));
    }
  }

  private static void require(Config config, String key, String required) {
    String actual = config.getValue(key, String.class);
    boolean matches =
        Arrays.stream(actual.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .allMatch(required::equals);
    if (!matches || actual.isBlank()) {
      throw unsafeOverride(key, actual, required);
    }
  }

  private static IllegalStateException unsafeOverride(String key, String actual, String required) {
    return new IllegalStateException(
        "Unsafe Flyway configuration: " + key + "=" + actual + "; required value is " + required);
  }
}
