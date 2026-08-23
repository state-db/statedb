package app.statedb.persistence.stack;

import app.statedb.persistence.NameGrammar;
import app.statedb.persistence.PersistenceExceptions;
import app.statedb.persistence.lock.StackLockedException;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Moves a stack to a new name and/or project — {@code pulumi stack rename}. The Pulumi cloud
 * backend sends a single rename call and leaves all state rewriting to the server, so this does
 * three things in one transaction:
 *
 * <ol>
 *   <li>Moves the {@code stacks} row metadata (name + project, and the reserved {@code
 *       pulumi:project} tag on a cross-project move). Internal references key on the row's UUID, so
 *       this alone keeps {@code ls}/{@code rm}/{@code get} working.
 *   <li>Rewrites the stored checkpoint so the name strings Pulumi bakes into it — resource URNs and
 *       the {@code service} secrets-provider state — match the new identity. Without this, secret
 *       decryption posts to the old stack path and 404s, and the next {@code up} sees every
 *       resource's URN as foreign. See {@link CheckpointRenamer}.
 *   <li>Records the rename as a terminal {@link StackUpdate} (kind {@code rename}) so it shows up
 *       in history/audit. Like {@link StackImporter}, this is an instantaneous server-side
 *       operation with no lease/poll, so the update is inserted already {@code SUCCEEDED}.
 * </ol>
 *
 * <p>Like {@link StackImporter}, this is a lease-less write: it takes a {@code PESSIMISTIC_WRITE}
 * row lock and refuses to run while an update is in progress so it can't race a live {@code up}.
 * Owner cannot change via a rename — the org is fixed by the request path — so only the project is
 * upserted (a rename into a not-yet-existing project succeeds like a first {@code init} would).
 */
@ApplicationScoped
public class StackRenamer {

  // Reserved tag Pulumi sets to the stack's project; it must track a cross-project move or
  // GET/list responses report a project that disagrees with the stack's actual project.
  private static final String PROJECT_TAG = "pulumi:project";

  @Inject ProjectUpserter projectUpserter;

  @Inject StackAssembler assembler;

  @Inject CheckpointRenamer checkpointRenamer;

  /**
   * @return the id of the recorded {@code rename} update, or {@code null} if the rename was a
   *     no-op.
   * @throws StackLockedException (409) if an update is in progress for the stack.
   * @throws StackAlreadyExistsException (409) if the target {@code (org, project, name)} is taken.
   */
  @Transactional
  public UUID rename(
      StackRef ref,
      String newName,
      String newProject,
      UUID startedByUserId,
      UUID startedByApiTokenId) {
    String targetProject = isBlank(newProject) ? ref.projectName() : newProject;
    String targetName = isBlank(newName) ? ref.stackName() : newName;
    NameGrammar.requireValid("project name", targetProject);
    NameGrammar.requireValid("stack name", targetName);
    if (targetProject.equals(ref.projectName()) && targetName.equals(ref.stackName())) {
      return null;
    }

    Stack stack =
        Stack.<Stack>find("id", ref.stackId())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    if (stack == null) {
      return null;
    }
    if (stack.activeUpdateId != null
        || StackUpdate.count(
                "stackId = ?1 and status = ?2", ref.stackId(), StackUpdate.Status.IN_PROGRESS)
            > 0) {
      throw new StackLockedException(ref.stackId());
    }

    UUID projectId = projectUpserter.upsert(ref.orgId(), targetProject);
    stack.projectId = projectId;
    stack.name = targetName;
    if (!targetProject.equals(ref.projectName())
        && stack.tags != null
        && stack.tags.containsKey(PROJECT_TAG)) {
      // Reassign (not mutate-in-place) so the JSON column is dirty-checked.
      Map<String, String> tags = new HashMap<>(stack.tags);
      tags.put(PROJECT_TAG, targetProject);
      stack.tags = tags;
    }
    try {
      stack.persistAndFlush();
    } catch (PersistenceException e) {
      if (PersistenceExceptions.isUniqueViolation(e)) {
        throw new StackAlreadyExistsException(ref.orgName(), targetProject, targetName);
      }
      throw e;
    }

    StackState state =
        StackState.<StackState>find("stackId", ref.stackId())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
    Integer installedSnapshotVersion = null;
    if (state != null && (state.baseSnapshotId != null || state.journalSeq != 0)) {
      installedSnapshotVersion = rewriteCheckpoint(state, ref.stackId(), targetName, targetProject);
    }

    return recordRename(
        stack, state, installedSnapshotVersion, startedByUserId, startedByApiTokenId);
  }

  // Rewrite and reinstall the checkpoint as a fresh compacted base snapshot. Assembling the current
  // deployment and rewriting it once is simpler — and avoids rewriting URNs across N journal deltas
  // — than mutating the base snapshot and every journal row in place; replay starts after the new
  // compaction boundary, so the old (stale-URN) journal rows are superseded.
  private int rewriteCheckpoint(
      StackState state, UUID stackId, String newStack, String newProject) {
    ObjectNode deployment = (ObjectNode) assembler.assemble(stackId).deployment();
    checkpointRenamer.rewrite(deployment, newStack, newProject);

    int snapshotVersion = state.baseSnapshotVersion + 1;
    Snapshot snapshot = new Snapshot();
    snapshot.stackId = stackId;
    snapshot.version = snapshotVersion;
    snapshot.checkpoint = deployment;
    snapshot.compactedFromJournalSeq = state.journalSeq;
    snapshot.resourceCount = deployment.path("resources").size();
    snapshot.persistAndFlush();

    state.baseSnapshotId = snapshot.id;
    state.baseSnapshotVersion = snapshotVersion;
    state.journalEntriesSinceCompaction = 0;
    return snapshotVersion;
  }

  // Insert the rename as a terminal update. The per-stack version is MAX+1, race-safe under the
  // stacks row lock held above (the same lock StackUpdateStarter takes). A rename spans no journal,
  // so start/end seq both point at the current journal head.
  private UUID recordRename(
      Stack stack,
      StackState state,
      Integer installedSnapshotVersion,
      UUID startedByUserId,
      UUID startedByApiTokenId) {
    StackUpdate latest =
        StackUpdate.<StackUpdate>find("stackId = ?1 order by version desc", stack.id).firstResult();
    long journalSeq = state == null ? 0L : state.journalSeq;
    Instant now = Instant.now();

    StackUpdate update = new StackUpdate();
    update.stackId = stack.id;
    update.kind = StackUpdate.Kind.RENAME;
    update.status = StackUpdate.Status.SUCCEEDED;
    update.version = (latest == null ? 0 : latest.version) + 1;
    update.startJournalSeq = journalSeq;
    update.endJournalSeq = journalSeq;
    // Lineage fence for historical export: the rewritten snapshot supersedes the stale-URN
    // journal rows behind it, so versioned assembly of earlier versions must not use it.
    update.baseSnapshotVersion = installedSnapshotVersion;
    update.startedByUserId = startedByUserId;
    update.startedByApiTokenId = startedByApiTokenId;
    update.startedAt = now;
    update.endedAt = now;
    update.persistAndFlush();

    stack.version++;
    return update.id;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
