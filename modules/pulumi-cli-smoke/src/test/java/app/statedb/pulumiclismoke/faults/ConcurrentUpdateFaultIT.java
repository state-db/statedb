package app.statedb.pulumiclismoke.faults;

import static app.statedb.testsupport.TestTransactions.read;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * Two CI runners fire `pulumi up` on the same stack: exactly one may hold the lease. The loser must
 * fail fast with a deterministic error while the winner's update is untouched — the single most
 * common "weird production thing" a state backend sees.
 */
@QuarkusTest
class ConcurrentUpdateFaultIT extends PulumiFaultBase {

  private static final String SLOW_PROJECT =
      """
      name: fault-test
      runtime: yaml
      resources:
        slow:
          type: command:local:Command
          properties:
            create: "sleep 600"
      """;

  @Test
  @FaultScenario(
      "Two concurrent `pulumi up` runs on one stack: exactly one wins the lease; the loser fails"
          + " fast with a conflict and never mutates state")
  void secondConcurrentUpIsRefused() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(SLOW_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    UUID stackId = stackId(PROJECT, STACK);

    execDetached("pulumi up --yes --non-interactive --skip-preview");
    awaitTrue("first up to hold the lease", Duration.ofMinutes(3), () -> lockExists(stackId));

    GenericContainer<?> rival = singleProjectContainer(SLOW_PROJECT);
    rival.start();
    try {
      rival.execInContainer("sh", "-lc", "cd /workspace && pulumi login " + backendUrl);
      rival.execInContainer(
          "sh", "-lc", "cd /workspace && pulumi stack select " + ORG + "/" + PROJECT + "/" + STACK);
      Container.ExecResult loser =
          rival.execInContainer(
              "sh", "-lc", "cd /workspace && pulumi up --yes --non-interactive --skip-preview");

      assertNotEquals(0, loser.getExitCode(), "the second up must be refused");
      String output = (loser.getStdout() + loser.getStderr()).toLowerCase();
      assertTrue(
          output.contains("conflict") || output.contains("in progress") || output.contains("409"),
          () -> "loser should see a conflict error:\n" + loser.getStdout() + loser.getStderr());

      // The winner's lease and update are untouched; the loser's orphaned attempt is terminal.
      assertTrue(lockExists(stackId), "winner's lease must survive the losing attempt");
      long inProgress =
          read(
              () ->
                  StackUpdate.count(
                      "stackId = ?1 and status = ?2", stackId, StackUpdate.Status.IN_PROGRESS));
      assertTrue(
          inProgress <= 1, "at most the winning update may remain in progress, got " + inProgress);
    } finally {
      rival.stop();
      killCliHard();
    }
  }
}
