package app.statedb.app;

import static app.statedb.testsupport.TestTransactions.read;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.StackCreator;
import java.util.Map;
import java.util.UUID;

/** Commits an organization with a {@code proj/dev} stack for services that open their own tx. */
public final class CommittedStack {
  private CommittedStack() {}

  public static UUID create(StackCreator stackCreator, String orgName, String displayName) {
    return read(
        () -> {
          Organization org = new Organization();
          org.name = orgName;
          org.displayName = displayName;
          org.persistAndFlush();
          return stackCreator.create(org.id, orgName, "proj", "dev", Map.of()).stackId();
        });
  }
}
