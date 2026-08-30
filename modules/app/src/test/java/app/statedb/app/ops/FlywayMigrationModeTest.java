package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FlywayMigrationModeTest {

  private static final String PROBE_SCHEMA = "flyway_nontransactional";

  @Inject AgroalDataSource dataSource;

  @Test
  void embeddedFlywayRunsNontransactionalAndRepeatableMigrations() throws Exception {
    Flyway probe =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/nontransactional")
            .defaultSchema(PROBE_SCHEMA)
            .schemas(PROBE_SCHEMA)
            .createSchemas(true)
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .load();

    probe.migrate();

    assertEquals("002", probe.info().current().getVersion().getVersion());
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_views"
                    + " WHERE schemaname = 'flyway_nontransactional'"
                    + " AND viewname = 'flyway_nontransactional_probe_view'")) {
      result.next();
      assertEquals(1, result.getInt(1));
    }
  }
}
