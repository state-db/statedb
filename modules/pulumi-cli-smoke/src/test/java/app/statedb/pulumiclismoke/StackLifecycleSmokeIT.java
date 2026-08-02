package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke coverage for stack lifecycle and whole-checkpoint state I/O:
 *
 * <pre>
 *   stack init / ls / rm      createsListsAndRemovesAnEmptyStack
 *   stack export / import     importsAndExportsAStackCheckpoint
 *   stack export --version    exportsAPriorVersionAfterTheStackIsEmptied
 * </pre>
 */
@QuarkusTest
class StackLifecycleSmokeIT extends PulumiSmokeBase {

  private static final String PET_TYPE = "random:index/randomPet:RandomPet";

  @Test
  void createsListsAndRemovesAnEmptyStack() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = recorderProjectContainer();
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);

    JsonNode stacksAfterInit = execJson("pulumi stack ls --json");
    assertTrue(
        stackNames(stacksAfterInit).contains(STACK),
        () -> "stack ls should include " + STACK + ":\n" + stacksAfterInit);

    execResult("pulumi stack rm " + STACK + " --yes");

    JsonNode stacksAfterRemove = execJson("pulumi stack ls --json");
    assertFalse(
        stackNames(stacksAfterRemove).contains(STACK),
        () -> "stack ls should not include " + STACK + ":\n" + stacksAfterRemove);
  }

  @Test
  void importsAndExportsAStackCheckpoint() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = recorderProjectContainer();
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi stack export --file /workspace/import.json");
    JsonNode before = execJson("cat /workspace/import.json");

    execResult("pulumi stack import --file /workspace/import.json");

    JsonNode after = execJson("pulumi stack export");
    assertEquals(normalizeExport(before), normalizeExport(after));
  }

  /**
   * {@code pulumi stack export --version N} reads a completed update by version number. The stack
   * is destroyed first so the historical answer cannot come from the head state: after the destroy
   * the current export is empty, while version 1 still has to carry everything the {@code up}
   * created.
   */
  @Test
  void exportsAPriorVersionAfterTheStackIsEmptied() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = recorderProjectContainer();
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");

    JsonNode atVersionOne = execJson("pulumi stack export --version 1");
    requireResource(atVersionOne, PET_TYPE, "pet");

    execResult("pulumi destroy --yes --non-interactive --skip-preview");

    JsonNode head = execJson("pulumi stack export");
    assertTrue(
        head.required("deployment").required("resources").isEmpty(),
        () -> "the destroy should leave the head export empty:\n" + head);

    // The same version still assembles to the same deployment: the export replays the journal up
    // to version 1's boundary, so the destroy that came after it must not show through. Only the
    // manifest differs — its timestamp is stamped when the deployment is assembled.
    JsonNode afterDestroy = execJson("pulumi stack export --version 1");
    assertEquals(normalizeExport(atVersionOne), normalizeExport(afterDestroy));

    // The destroy is itself a version; addressing it returns the emptied state, and there is no
    // version 3 to address.
    JsonNode atVersionTwo = execJson("pulumi stack export --version 2");
    assertTrue(
        atVersionTwo.required("deployment").required("resources").isEmpty(),
        () -> "version 2 is the destroy, so its export should be empty:\n" + atVersionTwo);
    assertFalse(
        execAllowingFailure("pulumi stack export --version 3").getExitCode() == 0,
        "there is no version 3 to export");
  }

  private static JsonNode normalizeExport(JsonNode exported) {
    ObjectNode normalized = exported.deepCopy();
    JsonNode deployment = normalized.get("deployment");
    if (deployment instanceof ObjectNode object) {
      object.remove("manifest");
      JsonNode metadata = object.get("metadata");
      if (metadata != null && metadata.isObject() && metadata.isEmpty()) {
        object.remove("metadata");
      }
    }
    return normalized;
  }
}
