package io.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 *   stack change-secrets-provider                  reEncryptsSecretsUnderANewProvider
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

  @Test
  void reEncryptsSecretsUnderANewProvider() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(inspectionProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive");

    JsonNode before = execJson("pulumi stack export");

    // Moving from the service-managed key to a passphrase decrypts every secret against us and
    // writes the re-encrypted checkpoint back through POST /import.
    execResult("pulumi stack change-secrets-provider passphrase");

    JsonNode after = execJson("pulumi stack export");
    assertEquals(
        "passphrase",
        after.required("deployment").required("secrets_providers").required("type").asText(),
        () -> "the checkpoint should record the new secrets provider:\n" + after);
    assertNotEquals(
        before.required("deployment").required("secrets_providers"),
        after.required("deployment").required("secrets_providers"),
        "the secrets provider block should have been rewritten");
    assertFalse(
        after.toString().contains(SECRET_VALUE),
        () -> "the re-encrypted export must not leak the secret:\n" + after);

    // The migrated checkpoint is still readable: the CLI parses what we handed back and still knows
    // which output is secret. Two things are deliberately not asserted, because both reproduce
    // identically against `file://` state on the pinned CLI and so are backend-independent: reading
    // the value back yields null, and a following update fails with "incorrect passphrase".
    JsonNode outputs = execJson("pulumi stack output --json");
    assertEquals(PLAIN_VALUE, outputs.required("plain").asText());
    assertEquals("[secret]", outputs.required("secret").asText());
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
