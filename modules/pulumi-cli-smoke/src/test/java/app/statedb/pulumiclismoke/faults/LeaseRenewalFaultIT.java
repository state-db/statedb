package app.statedb.pulumiclismoke.faults;

import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards the 5-minute {@code lease-ttl}, which is only safe because the CLI renews on its own. */
@QuarkusTest
class LeaseRenewalFaultIT extends PulumiFaultBase {

  // The CLI renews once 150s of its assumed 5-minute lease have elapsed.
  private static final String SLOW_PROJECT =
      """
      name: fault-test
      runtime: yaml
      resources:
        slow:
          type: command:local:Command
          properties:
            create: "sleep 200"
      """;

  @Test
  @FaultScenario(
      "A `pulumi up` running past 2.5 minutes triggers the CLI's own lease renewal: heartbeat_at"
          + " advances and the lease token rotates without the operator running `pulumi cancel` or"
          + " otherwise touching the lease")
  void longRunningUpRenewsTheLeaseBeforeCompleting() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(SLOW_PROJECT);
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    UUID stackId = stackId(PROJECT, STACK);

    execDetached("pulumi up --yes --non-interactive --skip-preview");
    awaitTrue("up to hold the lease", Duration.ofMinutes(3), () -> lockExists(stackId));

    Instant initialHeartbeat = readLock(stackId).heartbeatAt;
    byte[] initialTokenHash = readLock(stackId).lockTokenHash;

    awaitTrue(
        "the CLI to renew the lease past its assumed 2.5-minute half-life",
        Duration.ofMinutes(3),
        () -> {
          StackLock lock = readLock(stackId);
          return lock.heartbeatAt.isAfter(initialHeartbeat)
              && !Arrays.equals(lock.lockTokenHash, initialTokenHash);
        });

    awaitTrue(
        "the up to finish now that the slow create has completed",
        Duration.ofMinutes(2),
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        StackUpdate.count(
                                "stackId = ?1 and status = ?2",
                                stackId,
                                StackUpdate.Status.SUCCEEDED)
                            == 1));
  }

  private StackLock readLock(UUID stackId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> StackLock.<StackLock>find("stackId", stackId).singleResult());
  }
}
