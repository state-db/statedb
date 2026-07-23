package io.statedb.pulumiclismoke.faults;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.pulumiclismoke.PulumiSmokeBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Shared harness for the fault-injection {@code *FaultIT} classes: everything from {@link
 * PulumiSmokeBase} plus process-kill, lease-expiry, and DB-poll helpers (the detached-exec and
 * await helpers live on {@link PulumiSmokeBase} — the watch smoke test needs them too). Each
 * scenario is annotated with {@link FaultScenario}; results land in the State Safety Report via
 * {@link StateSafetyReport}.
 */
@ExtendWith(StateSafetyReport.class)
public abstract class PulumiFaultBase extends PulumiSmokeBase {

  protected static final String PROJECT = "fault-test";

  /** SIGKILLs every pulumi process in the container — the CLI gets no chance to flush or unlock. */
  protected void killCliHard() throws Exception {
    pulumi.execInContainer("sh", "-lc", "pkill -9 -f pulumi || true");
  }

  protected UUID stackId(String project, String stack) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Organization org = Organization.<Organization>find("name", ORG).singleResult();
              Project p =
                  Project.<Project>find("orgId = ?1 and name = ?2", org.id, project).singleResult();
              return Stack.<Stack>find("projectId = ?1 and name = ?2", p.id, stack)
                  .singleResult()
                  .id;
            });
  }

  protected boolean lockExists(UUID stackId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> io.statedb.persistence.lock.StackLock.count("stackId", stackId) > 0);
  }

  /**
   * Fault injection: expires the stack's lease at the DB level, standing in for the lease TTL
   * elapsing after the holder died (the TTL itself is an hour — too long to wait in a test).
   */
  protected void expireLease(UUID stackId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                io.statedb.persistence.lock.StackLock.update(
                    "expiresAt = ?1, maxExpiresAt = ?1 where stackId = ?2",
                    Instant.now().minusSeconds(1),
                    stackId));
  }

  /**
   * True once a Begin for the given resource type is open. Await this before killing the CLI: an
   * early resource's pending op can open and close in milliseconds, but a {@code
   * command:local:Command} whose create sleeps keeps its Begin open, making the kill window
   * deterministic.
   */
  protected boolean pendingOperationOpen(UUID stackId, String resourceTypePrefix) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                io.statedb.persistence.journal.PendingOperation.count(
                        "stackId = ?1 and resourceType like ?2", stackId, resourceTypePrefix + "%")
                    > 0);
  }
}
