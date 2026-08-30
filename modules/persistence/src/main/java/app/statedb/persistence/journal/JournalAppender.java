package app.statedb.persistence.journal;

import app.statedb.persistence.PersistenceExceptions;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.lock.LeaseRevalidator;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
 * {@code UNIQUE(stack_id, sequence_id)} constraints are layered defenses. A retry that re-sends an
 * already committed batch is accepted as an idempotent no-op; a duplicate wire ID with different
 * contents lands here as a {@link JournalSequenceConflictException} (→ 409).
 *
 * <p>The lease filter has validated the bearer token before this method runs, but filter-time
 * validation cannot prevent a concurrent {@code /complete} (or {@code DELETE /update/{id}}) from
 * removing the lock row between admission and the actual write. After taking the {@code stack_state
 * FOR UPDATE} we therefore call {@link LeaseRevalidator#requireLive(UUID)} inside the same
 * transaction — that locks the {@code stack_locks} row through commit, so any completer or
 * canceller that runs concurrently either blocks (when we ran first) or finds the lock already gone
 * (when they ran first, in which case {@code requireLive} throws). Without this, a PATCH admitted
 * before {@code /complete} could land journal entries past {@code stack_updates.end_journal_seq}.
 */
@ApplicationScoped
public class JournalAppender {

  private static final short MIN_KIND = 0;
  private static final short MAX_KIND = 7;

  // Kinds 0..7 per design/db-schema.md §Journal Entry Payload Structures (pinned to Pulumi
  // v3.242.0).
  @Inject EntityManager em;

  @Inject LeaseRevalidator leaseRevalidator;

  @Transactional
  public void append(UUID stackId, UUID updateId, UUID leaseId, List<JsonNode> rawEntries) {
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
        throw new RequestValidationException(e.getMessage(), e);
      }
      if (kind < MIN_KIND || kind > MAX_KIND) {
        throw new RequestValidationException(
            "unknown journal entry kind " + kind + " (supported: 0..7)");
      }
      if (seq <= 0) {
        throw new RequestValidationException("sequenceID must be positive, got " + seq);
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

    // 2a. Revalidate the lease inside the tx. If /complete (or a cancel) ran between filter
    //     admission and our stack_state lock, the lock row is gone and requireLive throws — so we
    //     never advance journal_seq past end_journal_seq. Lock order is stack_state → stack_locks,
    //     matching StackUpdateCompleter, so no deadlock.
    leaseRevalidator.requireLive(leaseId);

    StackUpdate update =
        StackUpdate.<StackUpdate>findByIdOptional(updateId)
            .orElseThrow(() -> new RequestValidationException("update does not exist"));
    if (update.dryRun) {
      throw new RequestValidationException("dry-run updates cannot append journal entries");
    }

    // 3. Atomically capture the per-update offset on first PATCH; otherwise read the existing one.
    //    Reuses stack_updates.start_journal_seq as the offset.
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

    List<JournalEntryView> viewsToInsert = missingEntries(updateId, offset, views);
    if (viewsToInsert.isEmpty()) {
      return;
    }

    // 4. Insert journal rows. Insertion order is arrival order; replay sorts by sequence_id.
    for (JournalEntryView v : viewsToInsert) {
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
      if (PersistenceExceptions.isUniqueViolation(e)) {
        throw new JournalSequenceConflictException(updateId, e);
      }
      throw e;
    }

    // 6. Advance stack_state. GREATEST guards against earlier PATCHes that already advanced past
    //    this batch's max (out-of-order arrival across PATCHes).
    em.createNativeQuery(
            "UPDATE stack_state "
                + "   SET journal_seq = GREATEST(journal_seq, ?1), "
                + "       journal_entries_since_compaction = journal_entries_since_compaction + ?2 "
                + " WHERE stack_id = ?3")
        .setParameter(1, offset + maxWireInBatch + 1)
        .setParameter(2, viewsToInsert.size())
        .setParameter(3, stackId)
        .executeUpdate();
  }

  private List<JournalEntryView> missingEntries(
      UUID updateId, long offset, List<JournalEntryView> views) {
    List<Long> wireIds = views.stream().map(JournalEntryView::sequenceId).toList();
    List<JournalEntry> existing =
        JournalEntry.<JournalEntry>list(
            "updateId = ?1 and wireSequenceId in ?2", updateId, wireIds);
    if (existing.isEmpty()) {
      return views;
    }

    Map<Long, JournalEntry> byWire = new HashMap<>();
    for (JournalEntry entry : existing) {
      byWire.put(entry.wireSequenceId, entry);
    }
    List<JournalEntryView> missing = new ArrayList<>();
    for (JournalEntryView view : views) {
      JournalEntry entry = byWire.get(view.sequenceId());
      if (entry == null) {
        missing.add(view);
      } else if (!sameEntry(entry, offset, view)) {
        throw new JournalSequenceConflictException(updateId);
      }
    }
    return missing;
  }

  private boolean sameEntry(JournalEntry entry, long offset, JournalEntryView view) {
    return entry.sequenceId == offset + view.sequenceId()
        && entry.kind == view.kind()
        && Objects.equals(entry.operationId, view.operationId())
        && entry.version == view.version()
        && Objects.equals(entry.removeOld, view.removeOld())
        && Objects.equals(entry.removeNew, view.removeNew())
        && Objects.equals(entry.deleteOld, view.deleteOld())
        && Objects.equals(entry.deleteNew, view.deleteNew())
        && Objects.equals(entry.pendingReplacementOld, view.pendingReplacementOld())
        && Objects.equals(entry.pendingReplacementNew, view.pendingReplacementNew())
        && entry.isRefresh == view.isRefresh()
        && payloadEquals(entry.payload, view.payload())
        && Objects.equals(entry.resourceUrn, view.resourceUrn());
  }

  private boolean payloadEquals(JsonNode stored, JsonNode incoming) {
    // jsonb canonicalizes numerics and object representation; use Postgres' own equality here so
    // retry detection matches the durable payload, not Jackson node-shape details.
    return (Boolean)
        em.createNativeQuery("SELECT CAST(?1 AS jsonb) = CAST(?2 AS jsonb)")
            .setParameter(1, stored.toString())
            .setParameter(2, incoming.toString())
            .getSingleResult();
  }
}
