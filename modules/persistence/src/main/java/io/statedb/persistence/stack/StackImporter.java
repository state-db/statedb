package io.statedb.persistence.stack;

import com.fasterxml.jackson.databind.JsonNode;
import io.statedb.persistence.RequestValidationException;
import io.statedb.persistence.lock.StackLockedException;
import io.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Replaces a stack's current deployment with an operator-supplied import checkpoint.
 *
 * <p>This write locks the stack and stack state rows because import is a cross-table state
 * transition: it must serialize with live updates, create an update record, install a new base
 * snapshot, and move the stack head atomically.
 */
@ApplicationScoped
public class StackImporter {

  @Inject EntityManager em;

  @Transactional
  public UUID importDeployment(
      UUID stackId, UUID startedByUserId, UUID startedByApiTokenId, JsonNode deployment) {
    if (deployment == null || !deployment.isObject()) {
      throw new RequestValidationException("import request must contain a deployment object");
    }

    // Pulumi stack import does not run the normal update lease protocol: the CLI POSTs the whole
    // checkpoint, gets an updateId, then polls that already-completed update. Since there is no
    // cloud work between start and finish, the backend protects the operation with row locks and an
    // active-update guard instead of requiring an update-token lease.
    Stack stack =
        Stack.<Stack>find("id", stackId).withLock(LockModeType.PESSIMISTIC_WRITE).singleResult();
    if (stack.activeUpdateId != null) {
      throw new StackLockedException(stackId);
    }

    StackState state =
        StackState.find("stackId", stackId).withLock(LockModeType.PESSIMISTIC_WRITE).singleResult();

    int nextUpdateVersion =
        ((Number)
                em.createQuery(
                        "SELECT COALESCE(MAX(u.version), 0) + 1 "
                            + "FROM StackUpdate u WHERE u.stackId = :stackId",
                        Number.class)
                    .setParameter("stackId", stackId)
                    .getSingleResult())
            .intValue();
    Instant now = Instant.now();

    StackUpdate update = new StackUpdate();
    update.stackId = stackId;
    update.kind = StackUpdate.Kind.IMPORT;
    update.status = StackUpdate.Status.SUCCEEDED;
    update.version = nextUpdateVersion;
    update.startJournalSeq = state.journalSeq;
    update.endJournalSeq = state.journalSeq;
    update.startedByUserId = startedByUserId;
    update.startedByApiTokenId = startedByApiTokenId;
    update.startedAt = now;
    update.endedAt = now;
    update.persistAndFlush();

    int snapshotVersion = state.baseSnapshotVersion + 1;
    Snapshot snapshot = new Snapshot();
    snapshot.stackId = stackId;
    snapshot.version = snapshotVersion;
    snapshot.checkpoint = deployment.deepCopy();
    snapshot.compactedFromJournalSeq = state.journalSeq;
    snapshot.resourceCount = snapshot.checkpoint.path("resources").size();
    snapshot.persistAndFlush();

    state.baseSnapshotId = snapshot.id;
    state.baseSnapshotVersion = snapshotVersion;
    state.journalEntriesSinceCompaction = 0;

    stack.version++;
    return update.id;
  }
}
