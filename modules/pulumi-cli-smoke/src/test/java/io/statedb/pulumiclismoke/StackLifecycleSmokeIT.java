package io.statedb.pulumiclismoke;

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
 *   stack init / ls / rm     createsListsAndRemovesAnEmptyStack
 *   stack export / import    importsAndExportsAStackCheckpoint
 * </pre>
 */
@QuarkusTest
class StackLifecycleSmokeIT extends PulumiSmokeBase {

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
