package app.statedb.pulumiclismoke.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.testsupport.pulumi.PulumiDeploymentAssert;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;

/**
 * The single most important fault: the operator's CLI dies mid-`pulumi up` with no chance to flush
 * or release the lease. state-db must (a) keep every journal entry that made it across the wire,
 * (b) keep serving reads with the in-flight create visible as a pending operation — the breadcrumb
 * that lets the operator find a possibly-orphaned cloud resource, (c) refuse a second update while
 * the lease is live, and (d) let the next `pulumi up` recover cleanly once the lease expires.
 */
@QuarkusTest
class CrashRecoveryFaultIT extends PulumiFaultBase {

  // `sleep 600` holds the command resource's create open, so the kill deterministically lands
  // mid-update with resource `before` committed and `slow` still in flight.
  private static final String CRASH_PROJECT =
      """
      name: fault-test
      runtime: yaml
      resources:
        before:
          type: random:RandomString
          properties:
            length: 8
            special: false
        slow:
          type: command:local:Command
          properties:
            create: "sleep 600"
          options:
            dependsOn:
              - ${before}
        after:
          type: random:RandomString
          properties:
            length: 8
            special: false
          options:
            dependsOn:
              - ${slow}
      """;

  private static final String RECOVERY_PROJECT = CRASH_PROJECT.replace("sleep 600", "true");

  @Test
  @FaultScenario(
      "CLI killed mid-update (SIGKILL, no flush): committed state survives, the in-flight create"
          + " stays visible as a pending operation, a concurrent update is refused while the lease"
          + " is live, and the next `pulumi up` recovers cleanly after lease expiry")
  void killedCliMidUpdateRecoversAfterLeaseExpiry() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(CRASH_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    UUID stackId = stackId(PROJECT, STACK);

    execDetached("pulumi up --yes --non-interactive --skip-preview");
    // Await the sleeping command's own Begin, not just any pending op: earlier resources'
    // Begins close within milliseconds, and killing in such a gap leaves nothing pending.
    awaitTrue(
        "up to be mid-flight (lease held, command create sleeping)",
        Duration.ofMinutes(3),
        () -> lockExists(stackId) && pendingOperationOpen(stackId, "command:local"));

    killCliHard();

    // Committed journal entries and the lease survive the crash; reads keep working and expose
    // the in-flight create as a pending operation.
    assertTrue(lockExists(stackId), "lease must survive the holder's death until its TTL");
    JsonNode exportAfterCrash = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfterCrash);
    JsonNode pending = exportAfterCrash.required("deployment").required("pending_operations");
    assertEquals(1, pending.size(), () -> "expected one pending create:\n" + pending);
    assertEquals("creating", pending.get(0).required("type").asText());
    assertTrue(
        pending.get(0).required("resource").required("urn").asText().contains("command:local"));

    // While the lease is live, another update must be refused.
    Container.ExecResult blocked =
        execAllowingFailure("pulumi up --yes --non-interactive --skip-preview");
    assertNotEquals(0, blocked.getExitCode(), "second up must fail while the lease is held");

    // Stand-in for the lease TTL elapsing after the holder died.
    expireLease(stackId);

    pulumi.copyFileToContainer(Transferable.of(RECOVERY_PROJECT), "/workspace/Pulumi.yaml");
    Container.ExecResult recovery = execResult("pulumi up --yes --non-interactive --skip-preview");
    assertTrue(
        recovery.getStdout().contains("created") || recovery.getStdout().contains("unchanged"),
        () -> "recovery up should apply resources:\n" + recovery.getStdout());

    JsonNode exportAfterRecovery = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfterRecovery);
    JsonNode deployment = exportAfterRecovery.required("deployment");
    assertTrue(
        deployment.required("resources").findValuesAsText("urn").stream()
            .anyMatch(urn -> urn.endsWith("::after")),
        "the resource behind the crash point must exist after recovery");
    // Pulumi's rule (JournalReplayer.GenerateDeployment): `creating` pending ops from the base
    // snapshot propagate into every later deployment — the operator's breadcrumb to a possibly
    // orphaned cloud resource — and "must require user intervention to be cleared or resolved"
    // (Pulumi's own words). A plain up must NOT silently drop it.
    assertEquals(
        1,
        deployment.required("pending_operations").size(),
        "the crash breadcrumb must survive a plain up");

    // The documented intervention clears it.
    execResult("pulumi refresh --yes --non-interactive --skip-preview --clear-pending-creates");

    JsonNode exportAfterRefresh = execJson("pulumi stack export");
    PulumiDeploymentAssert.assertIntegrityPasses(exportAfterRefresh);
    JsonNode refreshed = exportAfterRefresh.required("deployment");
    assertTrue(
        refreshed.path("pending_operations").isMissingNode()
            || refreshed.path("pending_operations").isEmpty(),
        () ->
            "refresh --clear-pending-creates must clear pending operations:\n"
                + refreshed.path("pending_operations"));
  }
}
