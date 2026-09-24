package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** A rewritten batch reports {@link Statement#SUCCESS_NO_INFO}, so counts reveal the flag. */
abstract class AbstractRewriteBatchedInsertsTest {

  // pgjdbc rewrites in power-of-two blocks; any other size leaves rows reported individually.
  private static final int BATCH_SIZE = 8;

  @Inject AgroalDataSource dataSource;

  abstract int expectedUpdateCount();

  @Test
  void batchedInsertsReportTheConfiguredUpdateCount() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement create = connection.createStatement()) {
        create.execute("CREATE TEMPORARY TABLE rewrite_batched_inserts_probe (id integer)");
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO rewrite_batched_inserts_probe (id) VALUES (?)")) {
        for (int i = 0; i < BATCH_SIZE; i++) {
          insert.setInt(1, i);
          insert.addBatch();
        }
        int[] updateCounts = insert.executeBatch();
        connection.commit();
        for (int updateCount : updateCounts) {
          assertEquals(expectedUpdateCount(), updateCount);
        }
      }
    }
  }
}
