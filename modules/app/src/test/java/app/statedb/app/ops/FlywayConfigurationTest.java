package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FlywayConfigurationTest {

  @Inject Flyway flyway;
  @Inject jakarta.persistence.EntityManager em;

  @Test
  void optionalReportsDoNotAlterOrReferenceUpdateHistory() {
    // Native SQL inspects the database contract rather than application entity behavior.
    Number columns =
        (Number)
            em.createNativeQuery(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'stack_updates'
                      AND column_name LIKE 'report_%'
                    """)
                .getSingleResult();
    assertEquals(0, columns.intValue());
    Number dependencies =
        (Number)
            em.createNativeQuery(
                    """
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid = 'report_deliveries'::regclass AND confrelid = 'stack_updates'::regclass
                    """)
                .getSingleResult();
    assertEquals(0, dependencies.intValue());
  }

  @Test
  void productionMigrationSafetySettingsReachEmbeddedFlyway() {
    Configuration configuration = flyway.getConfiguration();

    assertTrue(configuration.isValidateOnMigrate());
    assertTrue(configuration.isValidateMigrationNaming());
    assertTrue(configuration.isCleanDisabled());
    assertFalse(configuration.isBaselineOnMigrate());
    assertFalse(configuration.isOutOfOrder());
    assertFalse(configuration.isMixed());
    assertFalse(configuration.isGroup());
    assertFalse(configuration.isCreateSchemas());
    assertEquals("public", configuration.getDefaultSchema());
    assertArrayEquals(new String[] {"public"}, configuration.getSchemas());
    assertEquals("flyway_schema_history", configuration.getTable());
    assertEquals(10, configuration.getConnectRetries());
    assertEquals(6, configuration.getConnectRetriesInterval());
    assertEquals(300, configuration.getLockRetryCount());
    assertEquals(0, configuration.getIgnoreMigrationPatterns().length);
    assertEquals(
        "SET lock_timeout = '10s'; SET statement_timeout = '2min'", configuration.getInitSql());
    assertEquals("003", flyway.info().current().getVersion().getVersion());
    assertEquals("add vcs report delivery", flyway.info().current().getDescription());

    Config config = ConfigProvider.getConfig();
    assertTrue(config.getValue("quarkus.datasource.active", Boolean.class));
    assertTrue(config.getValue("quarkus.flyway.active", Boolean.class));
    assertTrue(config.getValue("quarkus.flyway.migrate-at-start", Boolean.class));
    assertFalse(config.getValue("quarkus.flyway.clean-at-start", Boolean.class));
    assertFalse(config.getValue("quarkus.flyway.repair-at-start", Boolean.class));
  }
}
