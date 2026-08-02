package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Smoke coverage for {@code pulumi import}. It runs the same update lifecycle as {@code up} —
 * {@code POST .../update} through journal entries to {@code /complete} — but the engine emits
 * import steps rather than creates, so the checkpoint it hands us is assembled differently.
 *
 * <p>The resource is dropped from state with {@code state delete} first, so the import re-adopts
 * one that genuinely exists on the provider side. {@code RandomString} is used because {@code
 * RandomPet} does not implement import; its inputs are constrained to lowercase letters so the
 * generated id is safe to pass back on a shell command line.
 */
@QuarkusTest
class ResourceImportSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT = "resource-import";
  private static final String STRING_TYPE = "random:index/randomString:RandomString";

  @Test
  void importsAnExistingResourceBackIntoState() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(oneStringProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");

    JsonNode created = requireResource(execJson("pulumi stack export"), STRING_TYPE, "word");
    String wordId = created.required("id").asText();

    // Forget the resource without destroying it: the value still exists, state stops tracking it.
    execResult("pulumi state delete '" + created.required("urn").asText() + "' --yes");
    assertTrue(
        findResource(execJson("pulumi stack export"), STRING_TYPE, "word").isEmpty(),
        "state delete should leave the stack without the resource");

    // Re-adopt it. --generate-code=false keeps the CLI from rewriting the program.
    execResult(
        "pulumi import "
            + STRING_TYPE
            + " word "
            + wordId
            + " --yes --skip-preview --generate-code=false --protect=false");

    JsonNode imported = requireResource(execJson("pulumi stack export"), STRING_TYPE, "word");
    assertEquals(wordId, imported.required("id").asText(), "the imported resource keeps its id");
    assertEquals(
        created.required("urn").asText(),
        imported.required("urn").asText(),
        "the imported resource lands back on its original URN");

    // Everything above is recorded, newest first: the `pulumi import` update, the checkpoint import
    // that `state delete` wrote, and the original `up`. The middle entry is what distinguishes a
    // client-side state edit from a program update in the history. (We do not assert a following
    // `up` is a no-op: whether the provider's importer reproduces every input is the provider's
    // business, not ours.)
    JsonNode history = execJson("pulumi stack history --json");
    assertEquals(
        List.of("update", "import", "update"),
        history.findValuesAsText("kind"),
        () -> "unexpected update history:\n" + history);
  }

  private static String oneStringProject() {
    return """
        name: %s
        runtime: yaml
        description: Smoke project for resource import.
        resources:
          word:
            type: random:RandomString
            properties:
              length: 8
              special: false
              upper: false
              numeric: false
        """
        .formatted(PROJECT);
  }
}
