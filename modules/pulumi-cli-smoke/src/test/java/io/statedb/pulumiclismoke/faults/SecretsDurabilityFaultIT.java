package io.statedb.pulumiclismoke.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.stack.CompactionJobEnqueuer;
import io.statedb.persistence.stack.CompactionWorker;
import io.statedb.testsupport.pulumi.DeploymentNormalizer;
import io.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Secrets are only safe if they survive everything the backend does behind the CLI's back.
 * Compaction must be invisible (byte-equal exports, Pulumi-valid), and a completely fresh CLI — new
 * machine, empty PULUMI_HOME — must still decrypt secret outputs afterwards.
 */
@QuarkusTest
class SecretsDurabilityFaultIT extends PulumiFaultBase {

  private static final String SECRET_PROJECT =
      """
      name: fault-test
      runtime: yaml
      resources:
        pet:
          type: random:RandomPet
          properties:
            length: 2
      outputs:
        plain: %s
        secret:
          fn::secret: %s
      """
          .formatted(PLAIN_VALUE, SECRET_VALUE);

  @Inject CompactionJobEnqueuer enqueuer;
  @Inject CompactionWorker worker;

  @Test
  @FaultScenario(
      "Secret outputs survive server-side compaction (exports byte-equal and Pulumi-valid) and"
          + " remain decryptable from a fresh CLI with an empty PULUMI_HOME")
  void secretsSurviveCompactionAndFreshCliReload() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(SECRET_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");
    UUID stackId = stackId(PROJECT, STACK);

    JsonNode exportBefore = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportBefore);

    QuarkusTransaction.requiringNew().run(() -> enqueuer.enqueue(stackId));
    assertEquals(1, worker.drainOnce(), "compaction should process the enqueued job");

    JsonNode exportAfter = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfter);
    assertEquals(
        DeploymentNormalizer.normalize(exportBefore),
        DeploymentNormalizer.normalize(exportAfter),
        "compaction must be invisible to the CLI");

    // A fresh CLI (empty PULUMI_HOME — new credentials cache, no plugin state) must still
    // decrypt the secret through the service secrets provider.
    GenericContainer<?> freshCli = singleProjectContainer(SECRET_PROJECT);
    freshCli.start();
    try {
      freshCli.execInContainer("sh", "-lc", "cd /workspace && pulumi login " + backendUrl);
      freshCli.execInContainer(
          "sh", "-lc", "cd /workspace && pulumi stack select " + ORG + "/" + PROJECT + "/" + STACK);
      String outputs =
          freshCli
              .execInContainer(
                  "sh", "-lc", "cd /workspace && pulumi stack output --json --show-secrets")
              .getStdout();
      JsonNode parsed = JSON.readTree(outputs);
      assertEquals(PLAIN_VALUE, parsed.required("plain").asText());
      assertEquals(SECRET_VALUE, parsed.required("secret").asText());
    } finally {
      freshCli.stop();
    }
  }
}
