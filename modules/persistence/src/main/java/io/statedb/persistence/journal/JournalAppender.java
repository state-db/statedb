package io.statedb.persistence.journal;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Appends a batch of Pulumi journal entries.
 *
 * <p>Replay-order contract: each entry's server-assigned {@code sequence_id} is {@code
 * stack_updates.start_journal_seq + entry.sequenceID}. The CLI assigns wire {@code sequenceID}s
 * from a monotonic atomic counter at entry-creation time, but concurrent resource workers can push
 * to the shared journal channel out of counter order — so wire IDs may arrive at the server
 * non-monotonically across PATCHes. By making the server's {@code sequence_id} a deterministic
 * function of the wire ID (plus a per-update offset captured on the first PATCH), {@code ORDER BY
 * sequence_id} always reconstructs wire order regardless of arrival order. See {@code
 * design/db-schema.md §JOURNALING} and the Slice 15 plan for the full analysis.
 *
 * <p>Concurrency: a {@code SELECT ... FOR UPDATE} on the {@code stack_state} row serializes
 * appenders cross-pod for the same stack. The {@code UNIQUE(update_id, wire_sequence_id)} and
 * {@code UNIQUE(stack_id, sequence_id)} constraints are layered defenses: any retry that re-sends a
 * committed wire ID lands here as a {@link JournalSequenceConflictException} (→ 409).
 *
 * <p>The lease filter has already validated the bearer token before this method runs; we trust that
 * {@code stackId} and {@code updateId} match the lease's view of the world.
 */
@ApplicationScoped
public class JournalAppender {

  private static final short MIN_KIND = 0;
  private static final short MAX_KIND = 7;

  // Kinds 0..7 per design/db-schema.md §Journal Entry Payload Structures (pinned to Pulumi
  // v3.242.0).
  private static final short KIND_BEGIN = 0;
  private static final short KIND_SUCCESS = 1;
  private static final short KIND_FAILURE = 2;
  private static final short KIND_REFRESH_SUCCESS = 3;

  // Mirrors the CHECK constraint on pending_operations.operation_type — we silently skip Begin
  // entries with an operation_type outside this set rather than crashing the whole PATCH, since
  // the surrounding journal_entries row is still valid for replay.
  private static final Set<String> VALID_OPERATION_TYPES =
      Set.of("creating", "updating", "deleting", "reading", "importing");

  @Inject EntityManager em;

  @Transactional
  public void append(UUID stackId, UUID updateId, List<JsonNode> rawEntries) {
    if (rawEntries == null || rawEntries.isEmpty()) {
      return;
    }

    // 1. Pure validation pass — no DB writes if anything is malformed.
    List<JournalEntryView> views = rawEntries.stream().map(JournalEntryView::new).toList();
    long maxWireInBatch = 0L;
    for (JournalEntryView v : views) {
      short kind;
      long seq;
      try {
        kind = v.kind();
        seq = v.sequenceId();
      } catch (IllegalArgumentException e) {
        throw new JournalValidationException(e.getMessage(), e);
      }
      if (kind < MIN_KIND || kind > MAX_KIND) {
        throw new JournalValidationException(
            "unknown journal entry kind " + kind + " (supported: 0..7)");
      }
      if (seq <= 0) {
        throw new JournalValidationException("sequenceID must be positive, got " + seq);
      }
      if (seq > maxWireInBatch) maxWireInBatch = seq;
    }

    // 2. Serialize appenders cross-pod for this stack. Also blocks /complete from racing.
    long currentJournalSeq =
        ((Number)
                em.createNativeQuery(
                        "SELECT journal_seq FROM stack_state WHERE stack_id = ?1 FOR UPDATE")
                    .setParameter(1, stackId)
                    .getSingleResult())
            .longValue();

    // 3. Atomically capture the per-update offset on first PATCH; otherwise read the existing one.
    //    Reuses stack_updates.start_journal_seq (added in V003) as the offset.
    em.createNativeQuery(
            "UPDATE stack_updates SET start_journal_seq = ?1 "
                + "WHERE id = ?2 AND start_journal_seq IS NULL")
        .setParameter(1, currentJournalSeq)
        .setParameter(2, updateId)
        .executeUpdate();
    long offset =
        ((Number)
                em.createNativeQuery("SELECT start_journal_seq FROM stack_updates WHERE id = ?1")
                    .setParameter(1, updateId)
                    .getSingleResult())
            .longValue();

    // 4. Insert journal rows. Insertion order is arrival order; replay sorts by sequence_id.
    for (JournalEntryView v : views) {
      long wireSeq = v.sequenceId();
      short kind = v.kind();
      JournalEntry je = new JournalEntry();
      je.stackId = stackId;
      je.updateId = updateId;
      je.sequenceId = offset + wireSeq;
      je.wireSequenceId = wireSeq;
      je.kind = kind;
      je.operationId = v.operationId();
      je.version = v.version();
      je.removeOld = v.removeOld();
      je.removeNew = v.removeNew();
      je.deleteOld = v.deleteOld();
      je.deleteNew = v.deleteNew();
      je.pendingReplacementOld = v.pendingReplacementOld();
      je.pendingReplacementNew = v.pendingReplacementNew();
      je.isRefresh = v.isRefresh();
      je.payload = v.payload();
      je.resourceUrn = v.resourceUrn();
      je.persist();
    }

    // 5. Flush so unique-constraint violations surface here as JournalSequenceConflictException
    //    rather than at commit time (where they'd escape the @Transactional boundary as a
    //    different exception type).
    try {
      em.flush();
    } catch (PersistenceException e) {
      if (isUniqueViolation(e)) {
        throw new JournalSequenceConflictException(updateId, e);
      }
      throw e;
    }

    // 6/7. Pending-operations maintenance. Order doesn't matter — a Success that arrives before
    //      its Begin will simply no-op the DELETE, and the late Begin will then leave a stale row
    //      that compaction will sweep for terminated updates.
    for (JournalEntryView v : views) {
      short kind = v.kind();
      Long opId = v.operationId();
      if (kind == KIND_BEGIN && opId != null) {
        String urn = v.resourceUrn();
        String resType = v.resourceType();
        String opType = v.operationType();
        if (urn != null
            && resType != null
            && opType != null
            && VALID_OPERATION_TYPES.contains(opType)) {
          em.createNativeQuery(
                  "INSERT INTO pending_operations "
                      + "(id, stack_id, update_id, operation_id, resource_urn, resource_type, "
                      + " operation_type, begin_journal_seq) "
                      + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) "
                      + "ON CONFLICT (update_id, operation_id) DO NOTHING")
              .setParameter(1, UUID.randomUUID())
              .setParameter(2, stackId)
              .setParameter(3, updateId)
              .setParameter(4, opId)
              .setParameter(5, urn)
              .setParameter(6, resType)
              .setParameter(7, opType)
              .setParameter(8, offset + v.sequenceId())
              .executeUpdate();
        }
      } else if ((kind == KIND_SUCCESS || kind == KIND_FAILURE || kind == KIND_REFRESH_SUCCESS)
          && opId != null) {
        em.createNativeQuery(
                "DELETE FROM pending_operations WHERE update_id = ?1 AND operation_id = ?2")
            .setParameter(1, updateId)
            .setParameter(2, opId)
            .executeUpdate();
      }
    }

    // 8. Advance stack_state. GREATEST guards against earlier PATCHes that already advanced past
    //    this batch's max (out-of-order arrival across PATCHes).
    em.createNativeQuery(
            "UPDATE stack_state "
                + "   SET journal_seq = GREATEST(journal_seq, ?1), "
                + "       journal_entries_since_compaction = journal_entries_since_compaction + ?2 "
                + " WHERE stack_id = ?3")
        .setParameter(1, offset + maxWireInBatch + 1)
        .setParameter(2, views.size())
        .setParameter(3, stackId)
        .executeUpdate();
  }

  private static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ConstraintViolationException) {
        return true;
      }
    }
    return false;
  }
}
