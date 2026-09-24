package app.statedb.app.ops;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Statement;

/** Default config: {@code reWriteBatchedInserts} is on. */
@QuarkusTest
class RewriteBatchedInsertsEnabledTest extends AbstractRewriteBatchedInsertsTest {

  @Override
  int expectedUpdateCount() {
    return Statement.SUCCESS_NO_INFO;
  }
}
