package io.statedb.pulumiclismoke.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * `pulumi cancel` is a recovery command against another stuck process, so this belongs in the fault
 * tier: it detaches a long-running CLI process, then uses a second container to revoke it.
 */
@QuarkusTest
class ForcedCancelFaultIT extends PulumiFaultBase {

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
      "`pulumi cancel --yes` from a second CLI revokes a stuck update's lease and exits cleanly")
  void secondCliCancelRevokesStuckLease() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(SLOW_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    UUID stackId = stackId(PROJECT, STACK);

    execDetached("pulumi up --yes --non-interactive --skip-preview");
    awaitTrue("first up to hold the lease", Duration.ofMinutes(3), () -> lockExists(stackId));

    GenericContainer<?> canceller = singleProjectContainer(SLOW_PROJECT);
    canceller.start();
    try {
      canceller.execInContainer("sh", "-lc", "cd /workspace && pulumi login " + backendUrl);
      canceller.execInContainer(
          "sh", "-lc", "cd /workspace && pulumi stack select " + ORG + "/" + PROJECT + "/" + STACK);
      Container.ExecResult result =
          canceller.execInContainer("sh", "-lc", "cd /workspace && pulumi cancel --yes");

      assertEquals(
          0,
          result.getExitCode(),
          () -> "pulumi cancel output:\n" + result.getStdout() + result.getStderr());
      awaitTrue(
          "forced cancel to remove the lock", Duration.ofSeconds(30), () -> !lockExists(stackId));
      assertFalse(lockExists(stackId), "forced cancel must remove the lock");

      Container.ExecResult preview =
          canceller.execInContainer(
              "sh", "-lc", "cd /workspace && pulumi preview --non-interactive --parallel 1");
      assertEquals(
          0,
          preview.getExitCode(),
          () ->
              "stack should be usable after forced cancel:\n"
                  + preview.getStdout()
                  + preview.getStderr());
      assertFalse(lockExists(stackId), "follow-up preview must release its lock");
    } finally {
      canceller.stop();
      killCliHard();
    }
  }
}
