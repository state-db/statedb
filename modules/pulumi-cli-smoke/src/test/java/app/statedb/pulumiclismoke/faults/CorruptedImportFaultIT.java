package app.statedb.pulumiclismoke.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.statedb.testsupport.pulumi.DeploymentNormalizer;
import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

/**
 * An operator hand-edits an exported checkpoint and imports it back. state-db's documented stance
 * is stricter than Pulumi Cloud: a deployment it cannot represent is rejected before persistence,
 * so the backend never carries known-bad state — the previous good state must remain untouched.
 */
@QuarkusTest
class CorruptedImportFaultIT extends PulumiFaultBase {

  private static final String SMALL_PROJECT =
      """
      name: fault-test
      runtime: yaml
      resources:
        pet:
          type: random:RandomPet
          properties:
            length: 2
      """;

  @Test
  @FaultScenario(
      "Corrupted checkpoint import (unsupported schema version) is rejected before persistence;"
          + " the previous good state is untouched and still Pulumi-valid")
  void corruptedImportIsRejectedAndStateUntouched() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(SMALL_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");

    execResult("pulumi stack export --file /workspace/good.json");
    JsonNode goodExport = execJson("cat /workspace/good.json");
    PulumiDeploymentAssert.assertIntegrityPasses(goodExport);

    // Tamper: claim a deployment schema version the backend does not support.
    execResult(
        "python3 -c \"import json; d = json.load(open('/workspace/good.json'));"
            + " d['version'] = 99; json.dump(d, open('/workspace/bad.json', 'w'))\"");

    Container.ExecResult rejected =
        execAllowingFailure("pulumi stack import --file /workspace/bad.json");
    assertNotEquals(0, rejected.getExitCode(), "import of an unsupported deployment must fail");

    JsonNode exportAfterRejection = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfterRejection);
    assertEquals(
        DeploymentNormalizer.normalize(goodExport),
        DeploymentNormalizer.normalize(exportAfterRejection),
        "a rejected import must leave the previous state untouched");
  }
}
