package app.statedb.app.ops;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;

/** {@code statedb.db.rewrite-batched-inserts=false}: pgjdbc must report a real count per row. */
@QuarkusTest
@TestProfile(RewriteBatchedInsertsDisabledTest.RewriteDisabledProfile.class)
class RewriteBatchedInsertsDisabledTest extends AbstractRewriteBatchedInsertsTest {

  @Override
  int expectedUpdateCount() {
    return 1;
  }

  public static final class RewriteDisabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("statedb.db.rewrite-batched-inserts", "false");
    }
  }
}
