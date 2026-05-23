package io.statedb.persistence.update;

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

  @Transactional
  public StartedUpdate start(
      UUID stackId,
      StackUpdate.Kind kind,
      UUID startedByUserId,
      UUID startedByApiTokenId,
      String clientRequestId,
      StartUpdateRequest req) {

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

    int nextVersion =
        ((Number)
                em.createNativeQuery(
                        "SELECT COALESCE(MAX(version), 0) + 1 FROM stack_updates WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .intValue();

    StackUpdate u = new StackUpdate();
    u.stackId = stackId;
    u.kind = kind;
    u.status = StackUpdate.Status.IN_PROGRESS;
    u.version = nextVersion;
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
}
