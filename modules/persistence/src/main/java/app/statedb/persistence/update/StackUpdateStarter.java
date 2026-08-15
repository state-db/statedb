package app.statedb.persistence.update;

import app.statedb.persistence.entitlement.EffectiveEntitlement;
import app.statedb.persistence.entitlement.Entitlements;
import app.statedb.persistence.stack.Stack;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Starts a new {@link StackUpdate} (preview / update / destroy). Per-stack {@code version} is
 * assigned under a row-level lock on the parent {@code stacks} row, which both serializes
 * concurrent starts on the same stack and lets us check the idempotency key after the lock is held
 * — so a retried POST with the same {@code clientRequestId} always returns the existing row rather
 * than racing a duplicate insert.
 */
@ApplicationScoped
public class StackUpdateStarter {

  @Inject EntityManager em;
  @Inject Entitlements entitlements;

  @Transactional
  public StartedUpdate start(
      UUID stackId,
      StackUpdate.Kind kind,
      UUID startedByUserId,
      UUID startedByApiTokenId,
      String clientRequestId,
      StartUpdateRequest req) {

    boolean dryRun = kind == StackUpdate.Kind.PREVIEW || isDryRun(req);
    EffectiveEntitlement entitlement =
        entitlements.requiresPaidEntitlement(kind, dryRun) ? entitlements.effective() : null;

    em.createNativeQuery("SELECT 1 FROM stacks WHERE id = ?1 FOR UPDATE")
        .setParameter(1, stackId)
        .getSingleResult();

    if (clientRequestId != null) {
      List<StackUpdate> existing =
          em.createQuery(
                  "SELECT u FROM StackUpdate u WHERE u.stackId = :s AND u.clientRequestId = :k",
                  StackUpdate.class)
              .setParameter("s", stackId)
              .setParameter("k", clientRequestId)
              .getResultList();
      if (!existing.isEmpty()) {
        StackUpdate u = existing.get(0);
        return new StartedUpdate(u.id, u.version);
      }
    }

    // Dry runs (pulumi preview, and the dry-run pass of up/refresh/destroy) do not consume the
    // applied-version sequence: versions address applied updates, 1-based and contiguous.
    if (entitlement != null && entitlement.tier() != EffectiveEntitlement.Tier.PAID) {
      entitlements.requireUpdateAllowed(Stack.count(), kind, dryRun, entitlement);
    }
    int version =
        dryRun
            ? 0
            : ((Number)
                    em.createNativeQuery(
                            "SELECT COALESCE(MAX(version), 0) + 1 FROM stack_updates WHERE stack_id = ?1")
                        .setParameter(1, stackId)
                        .getSingleResult())
                .intValue();

    StackUpdate u = new StackUpdate();
    u.stackId = stackId;
    u.kind = kind;
    u.status = StackUpdate.Status.IN_PROGRESS;
    u.version = version;
    u.dryRun = dryRun;
    u.clientRequestId = clientRequestId;
    u.config = req.config();
    u.metadata = req.metadata();
    u.options = req.options();
    u.description = req.description();
    u.runtime = req.runtime();
    u.pulumiVersion = req.pulumiVersion();
    u.message = req.message();
    u.startedByUserId = startedByUserId;
    u.startedByApiTokenId = startedByApiTokenId;
    u.startedAt = Instant.now();
    u.persistAndFlush();

    return new StartedUpdate(u.id, u.version);
  }

  private static boolean isDryRun(StartUpdateRequest req) {
    return req.options() != null && Boolean.TRUE.equals(req.options().get("dryRun"));
  }
}
