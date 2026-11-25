package app.statedb.pulumiclismoke.faults;

import static app.statedb.testsupport.TestTransactions.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.update.StackUpdate;
import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;

/**
 * A provider errors partway through a deployment (the classic mistyped program / quota error). The
 * resources that were created before the failure are real and must be in state; the failed resource
 * must not be; the update must be recorded as failed; and a corrected `pulumi up` must succeed.
 */
@QuarkusTest
class FailedDeploymentFaultIT extends PulumiFaultBase {

  private static final String FAILING_PROJECT =
      """
      name: fault-test
      runtime: yaml
      resources:
        ok:
          type: random:RandomString
          properties:
            length: 8
            special: false
        bad:
          type: command:local:Command
          properties:
            create: "exit 7"
          options:
            dependsOn:
              - ${ok}
      """;

  private static final String FIXED_PROJECT = FAILING_PROJECT.replace("exit 7", "true");

  @Test
  @FaultScenario(
      "Provider error mid-deployment: resources created before the failure stay in state, the"
          + " failed resource does not, the update is recorded as failed, and a corrected"
          + " `pulumi up` succeeds")
  void failedDeploymentKeepsCompletedResourcesAndRecovers() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(FAILING_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    UUID stackId = stackId(PROJECT, STACK);

    Container.ExecResult failed =
        execAllowingFailure("pulumi up --yes --non-interactive --skip-preview");
    assertNotEquals(0, failed.getExitCode(), "up must fail when the provider errors");

    awaitTrue(
        "the failed update to be recorded as failed",
        Duration.ofMinutes(1),
        () ->
            read(
                    () ->
                        StackUpdate.count(
                            "stackId = ?1 and status = ?2", stackId, StackUpdate.Status.FAILED))
                > 0);

    JsonNode exportAfterFailure = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfterFailure);
    List<String> urns =
        exportAfterFailure.required("deployment").required("resources").findValuesAsText("urn");
    assertTrue(
        urns.stream().anyMatch(urn -> urn.endsWith("::ok")),
        () -> "resource created before the failure must be in state: " + urns);
    assertTrue(
        urns.stream().noneMatch(urn -> urn.endsWith("::bad")),
        () -> "the failed resource must not be in state: " + urns);

    pulumi.copyFileToContainer(Transferable.of(FIXED_PROJECT), "/workspace/Pulumi.yaml");
    execResult("pulumi up --yes --non-interactive --skip-preview");

    JsonNode exportAfterFix = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfterFix);
    List<String> fixedUrns =
        exportAfterFix.required("deployment").required("resources").findValuesAsText("urn");
    assertTrue(fixedUrns.stream().anyMatch(urn -> urn.endsWith("::ok")));
    assertTrue(fixedUrns.stream().anyMatch(urn -> urn.endsWith("::bad")));
    assertEquals(
        1L,
        read(
            () ->
                StackUpdate.count(
                    "stackId = ?1 and status = ?2", stackId, StackUpdate.Status.SUCCEEDED)),
        "the corrected up must be recorded as succeeded");
  }
}
