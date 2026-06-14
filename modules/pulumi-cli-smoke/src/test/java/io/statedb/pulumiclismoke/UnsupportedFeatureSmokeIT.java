package io.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

/**
 * Smoke coverage for endpoints this backend deliberately rejects with 501 (Pulumi Cloud features we
 * do not implement). The contract is operational, not semantic: the CLI must fail cleanly — a
 * non-zero exit — rather than hang or treat the error as success.
 *
 * <pre>
 *   stack schedule list   rejectsScheduleAndWebhookCommands
 *   stack webhook list    rejectsScheduleAndWebhookCommands
 * </pre>
 *
 * <p>Add future unsupported-feature commands here as their 501 stubs land.
 */
@QuarkusTest
class UnsupportedFeatureSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT = "unsupported-feature";

  @Test
  void rejectsScheduleAndWebhookCommands() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(project());
    pulumi.start();

    // These commands only resolve the stack (GET stack) before calling the unsupported endpoint, so
    // an `up` is unnecessary — init is enough to make the stack exist.
    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);

    Container.ExecResult schedule = execAllowingFailure("pulumi stack schedule list");
    assertFalse(
        schedule.getExitCode() == 0,
        () ->
            "stack schedule list should fail against an unsupported backend:\n"
                + schedule.getStdout()
                + schedule.getStderr());

    Container.ExecResult webhook = execAllowingFailure("pulumi stack webhook list");
    assertFalse(
        webhook.getExitCode() == 0,
        () ->
            "stack webhook list should fail against an unsupported backend:\n"
                + webhook.getStdout()
                + webhook.getStderr());
  }

  private static String project() {
    return """
        name: %s
        runtime: yaml
        description: Smoke project for unsupported-feature checks.
        """
        .formatted(PROJECT);
  }
}
