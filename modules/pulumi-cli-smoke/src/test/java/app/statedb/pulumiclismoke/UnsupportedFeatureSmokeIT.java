package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

/**
 * Smoke coverage for endpoints this backend deliberately rejects (Pulumi Cloud features we do not
 * implement). The contract is operational, not semantic: the CLI must fail cleanly — a non-zero
 * exit — rather than hang or treat the error as success.
 *
 * <pre>
 *   stack schedule list   rejectsUnsupportedCloudCommands
 *   stack webhook list    rejectsUnsupportedCloudCommands
 *   org search            rejectsUnsupportedCloudCommands
 *   policy ls             rejectsUnsupportedCloudCommands
 *   env ls                rejectsUnsupportedCloudCommands
 * </pre>
 *
 * <p>The commands that surface our error message assert on that text, which is what proves the CLI
 * reached the stub rather than failing for some unrelated reason. It also pins that the CLI does
 * not swallow the rejection — these surfaces are the ones where a bodyless 404 would read as "no
 * results". The pinned CLI does not have every unsupported subcommand — {@code stack schedule},
 * {@code stack webhook}, {@code deployment}, {@code org member/role/webhook/audit-log/usage}, and
 * {@code package info} against a registry are all newer or resolve locally — so those get the
 * weaker exit-code check here and their real coverage from {@code UnsupportedFeatureContractTest}.
 * Add future unsupported-feature commands here as their stubs land.
 */
@QuarkusTest
class UnsupportedFeatureSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT = "unsupported-feature";
  private static final String NOT_SUPPORTED = "not supported by this backend";

  @Test
  void rejectsUnsupportedCloudCommands() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(project());
    pulumi.start();

    // These commands only resolve the stack (GET stack) before calling the unsupported endpoint, so
    // an `up` is unnecessary — init is enough to make the stack exist.
    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);

    assertRejected("pulumi stack schedule list");
    assertRejected("pulumi stack webhook list");

    // Org-, policy-, and ESC-scoped surfaces: the CLI prints "[501] <our message>", so these pin
    // that the request actually reached the stub.
    assertRejectedAsUnsupported("pulumi org search --query pulumi");
    assertRejectedAsUnsupported("pulumi policy ls " + ORG);
    assertRejectedAsUnsupported("pulumi env ls");
  }

  private void assertRejected(String command) throws Exception {
    Container.ExecResult result = execAllowingFailure(command);
    assertFalse(
        result.getExitCode() == 0,
        () ->
            command
                + " should fail against an unsupported backend:\n"
                + result.getStdout()
                + result.getStderr());
  }

  private void assertRejectedAsUnsupported(String command) throws Exception {
    Container.ExecResult result = execAllowingFailure(command);
    assertFalse(
        result.getExitCode() == 0,
        () ->
            command
                + " should fail against an unsupported backend:\n"
                + result.getStdout()
                + result.getStderr());
    assertContains(result.getStdout() + result.getStderr(), NOT_SUPPORTED);
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
