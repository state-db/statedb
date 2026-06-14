package io.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke coverage for stack metadata and inspection commands:
 *
 * <pre>
 *   stack tag set/ls/get/rm                       supportsTagGraphHistoryAndSelectCommands
 *   stack (bare) / graph / history / select       supportsTagGraphHistoryAndSelectCommands
 *   stack rename                                   renamesAStack
 * </pre>
 *
 * <p>({@code stack get} is not a real subcommand in the pinned CLI — bare {@code pulumi stack}
 * "accepts 0 arg(s)" — so it is covered by bare {@code stack} instead.)
 */
@QuarkusTest
class StackMetadataSmokeIT extends PulumiSmokeBase {

  private static final String INSPECT_PROJECT = "stack-subcommands";
  private static final String STAGING_STACK = "staging";

  @Test
  void supportsTagGraphHistoryAndSelectCommands() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(inspectionProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive");

    // tag round-trip: set a tag, see it via ls/get, then remove it and confirm it is gone.
    execResult("pulumi stack tag set env smoke");
    JsonNode tagsWithEnv = execJson("pulumi stack tag ls --json");
    assertEquals(
        "smoke",
        tagsWithEnv.path("env").asText(),
        () -> "stack tag ls should report the tag we just set:\n" + tagsWithEnv);
    assertContains(execResult("pulumi stack tag get env").getStdout(), "smoke");
    execResult("pulumi stack tag rm env");
    JsonNode tagsWithoutEnv = execJson("pulumi stack tag ls --json");
    assertFalse(
        tagsWithoutEnv.has("env"), () -> "stack tag rm should drop the tag:\n" + tagsWithoutEnv);

    // bare `stack` resolves and prints the current stack via GET stack/export.
    assertContains(execResult("pulumi stack").getStdout(), STACK);

    // graph builds a DOT file from the exported checkpoint.
    execResult("pulumi stack graph /workspace/graph.dot");
    assertContains(execResult("cat /workspace/graph.dot").getStdout(), "digraph");

    // history lists the update we just ran (previews and renames are excluded server-side).
    JsonNode history = execJson("pulumi stack history --json");
    assertTrue(history.isArray() && history.size() >= 1, () -> "expected >=1 update:\n" + history);
    assertEquals(
        "update", history.get(0).path("kind").asText(), () -> "newest update kind:\n" + history);

    // select / unselect are CLI-local state changes that still resolve the stack server-side.
    execResult("pulumi stack select " + STACK);
    execResult("pulumi stack unselect");
    execResult("pulumi stack select " + STACK);
  }

  @Test
  void renamesAStack() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(inspectionProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive");

    // rename moves the stack server-side (metadata + checkpoint rewrite); ls must reflect the move.
    execResult("pulumi stack rename " + STAGING_STACK);
    JsonNode stacks = execJson("pulumi stack ls --json");
    assertTrue(
        stackNames(stacks).contains(STAGING_STACK),
        () -> "stack ls should show the renamed stack:\n" + stacks);
    assertFalse(
        stackNames(stacks).contains(STACK),
        () -> "stack ls should no longer show the old name:\n" + stacks);
  }

  // Provider-free project (so `up` needs no plugin download) that still produces a real update, a
  // checkpoint to graph/export, and a secret output to exercise batch-decrypt.
  private static String inspectionProject() {
    return """
        name: %s
        runtime: yaml
        description: Stack metadata smoke project.
        outputs:
          plain: %s
          secret:
            fn::secret: %s
        """
        .formatted(INSPECT_PROJECT, PLAIN_VALUE, SECRET_VALUE);
  }
}
