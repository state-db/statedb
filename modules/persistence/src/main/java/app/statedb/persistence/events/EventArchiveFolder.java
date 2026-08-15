package app.statedb.persistence.events;

import app.statedb.persistence.update.StackUpdate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Page;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Folds a terminal update's {@code update_events} rows into one compressed {@link
 * UpdateEventArchive}, and unfolds them again on demand.
 *
 * <p>Events are observability data with no ordering invariant against {@code stack_state} (see
 * {@link EventBatchAppender}), so this worker takes none of the locks compaction and updates
 * serialize on and cannot interact with their mutual exclusion. The only row it locks is the {@code
 * stack_updates} row it is folding, with {@code SKIP LOCKED} — that is what makes concurrent pods
 * safe, and the archive's {@code update_id} primary key is the backstop.
 *
 * <p>Eligibility is a predicate, not a queue: an update is pending while {@code
 * stack_updates.events_folded_at} is null, and is swept once it is terminal and its grace period
 * has elapsed. A crash mid-fold rolls the transaction back — marker included — and leaves the
 * update pending, so there is nothing to reclaim and no job rows to reap.
 *
 * <p>The marker is set whether or not there was anything to fold, and that is the point of it:
 * imports, renames and runs that ended before their first event batch have no foldable events and
 * never will, so a sweep keyed off "has no archive yet" would re-prove that about all of them on
 * every tick, forever. Settling them takes them out of the pending set, and the partial index
 * behind it holds only outstanding work.
 *
 * <p>The grace period exists for live tailing. The console pages an in-flight run by {@code
 * sequence} and keeps polling after it ends; folding out from under a client that is still walking
 * the rows would be correct but wasteful, and the delay costs nothing.
 *
 * <p>Folding paces itself with a per-tick cap because {@code DELETE} leaves dead tuples in both the
 * heap and the TOAST table for autovacuum to reclaim.
 */
@ApplicationScoped
public class EventArchiveFolder {

  private static final Logger LOG = Logger.getLogger(EventArchiveFolder.class);

  /** Rows loaded per round trip while encoding, so one huge run does not have to fit in memory. */
  private static final int ENCODE_BATCH = 2_000;

  @Inject EntityManager em;

  @Inject EventArchiveCodec codec;

  /**
   * Set to {@code false} to stop folding. Existing archives keep being served — the read path
   * prefers an archive whenever one is present — so this is a complete rollback switch on its own;
   * {@link #unfold} turns individual archives back into rows.
   */
  @ConfigProperty(name = "statedb.events.archive.enabled", defaultValue = "true")
  boolean enabled;

  /** How long after an update ends before its events may be folded. */
  @ConfigProperty(name = "statedb.events.archive.grace-period", defaultValue = "1h")
  Duration gracePeriod;

  @ConfigProperty(name = "statedb.events.archive.per-tick-cap", defaultValue = "50")
  int perTickCap;

  // SKIP, not the default PROCEED: a tick that runs past the poll interval would otherwise be
  // joined by the next one, and two overlapping sweeps put 2x the per-tick cap of zstd encodes and
  // bulk deletes on this pod. Row locks stop the same update being folded twice; they do not bound
  // how much work the pod takes on at once.
  @Scheduled(
      every = "{statedb.events.archive.poll-interval}",
      concurrentExecution = ConcurrentExecution.SKIP)
  void scheduledTick() {
    foldOnce();
  }

  /** Folds up to the per-tick cap of eligible updates. Returns how many were folded. */
  public int foldOnce() {
    if (!enabled) {
      return 0;
    }
    Instant cutoff = Instant.now().minus(gracePeriod);
    int folded = 0;
    for (UUID updateId : candidates(cutoff)) {
      try {
        if (QuarkusTransaction.requiringNew().call(() -> fold(updateId, cutoff))) {
          folded++;
        }
      } catch (RuntimeException e) {
        // Nothing to record: the update stays eligible and the next tick retries it.
        LOG.warnf(e, "update=%s event archive fold failed", updateId);
      }
    }
    return folded;
  }

  @SuppressWarnings("unchecked")
  private List<UUID> candidates(Instant cutoff) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                em.createNativeQuery(
                        """
                        SELECT u.id
                          FROM stack_updates u
                         WHERE u.events_folded_at IS NULL
                           AND u.status <> 'in-progress'
                           AND u.ended_at < ?1
                         ORDER BY u.ended_at
                         LIMIT ?2
                        """)
                    .setParameter(1, cutoff)
                    .setParameter(2, perTickCap)
                    .getResultList());
  }

  /**
   * Folds one update inside the caller's transaction; also the single-update entry point for admin
   * and test callers. Returns false when the update was claimed by another pod or stopped being
   * eligible between selection and here.
   */
  public boolean fold(UUID updateId, Instant cutoff) {
    UUID stackId = claim(updateId, cutoff);
    if (stackId == null) {
      return false;
    }

    // Nothing foldable. Settle it so the sweep stops reconsidering it, but write no archive: an
    // archive is what tells readers the blob is authoritative, and an empty one would mean the
    // reader served nothing for a run whose summary row is still there.
    if (UpdateEvent.count("updateId = ?1 and kind <> ?2", updateId, UpdateEvent.SUMMARY_KIND)
        == 0) {
      markSettled(updateId);
      return false;
    }

    EventArchiveCodec.Encoder encoder = codec.encoder();
    int cursor = -1;
    while (true) {
      List<UpdateEvent> batch =
          UpdateEvent.<UpdateEvent>find(
                  "updateId = ?1 and sequence > ?2 order by sequence", updateId, cursor)
              .page(Page.ofSize(ENCODE_BATCH))
              .list();
      if (batch.isEmpty()) {
        break;
      }
      batch.forEach(row -> encoder.add(StoredEvent.of(row)));
      cursor = batch.getLast().sequence;
      // Let the batch go before loading the next one; nothing below reads these entities.
      em.clear();
    }

    // The update is terminal and its lease is gone, so no appender can be adding rows — but the
    // rows are about to be deleted, so prove the blob accounts for all of them anyway.
    long rowCount = UpdateEvent.count("updateId", updateId);
    if (rowCount != encoder.count()) {
      throw new IllegalStateException(
          "event archive for update %s encoded %d of %d rows"
              .formatted(updateId, encoder.count(), rowCount));
    }

    UpdateEventArchive archive = new UpdateEventArchive();
    archive.updateId = updateId;
    archive.stackId = stackId;
    archive.codec = EventArchiveCodec.ZSTD;
    archive.eventCount = encoder.count();
    archive.maxSequence = encoder.maxSequence();
    archive.events = encoder.finish();
    // Flush before the bulk delete: the delete's query space is update_events, so Hibernate's
    // space-based auto-flush would leave this insert pending and any failure would surface at
    // commit, outside this method.
    archive.persistAndFlush();

    UpdateEvent.delete("updateId = ?1 and kind <> ?2", updateId, UpdateEvent.SUMMARY_KIND);
    markSettled(updateId);
    LOG.debugf(
        "update=%s folded %d events into %d bytes",
        updateId, archive.eventCount, archive.events.length);
    return true;
  }

  /**
   * Records that the folder is done with this update, inside the caller's transaction, so it rolls
   * back with the fold rather than stranding an update that was marked but never archived.
   */
  private void markSettled(UUID updateId) {
    StackUpdate.update("eventsFoldedAt = ?1 where id = ?2", Instant.now(), updateId);
  }

  /**
   * Takes the update's row lock and re-checks eligibility under it. {@code SKIP LOCKED} means a pod
   * already folding this update is left to it rather than waited on, and the re-check closes the
   * window between candidate selection and the fold.
   */
  private UUID claim(UUID updateId, Instant cutoff) {
    List<?> rows =
        em.createNativeQuery(
                """
                SELECT u.stack_id
                  FROM stack_updates u
                 WHERE u.id = ?1
                   AND u.events_folded_at IS NULL
                   AND u.status <> 'in-progress'
                   AND u.ended_at < ?2
                   FOR UPDATE SKIP LOCKED
                """)
            .setParameter(1, updateId)
            .setParameter(2, cutoff)
            .getResultList();
    return rows.isEmpty() ? null : (UUID) rows.getFirst();
  }

  /**
   * Restores an archived update's rows and drops its archive. Admin and test entry point; the
   * rollback story for the whole feature is to disable folding and run this over the archives.
   *
   * <p>One transaction, like {@link #fold}. {@link UpdateEventReader} reads the rows and the
   * archive in a single snapshot, so it observes this either wholly or not at all; splitting the
   * row restore from the archive delete would only widen the window in which both exist.
   *
   * @return the number of events restored
   */
  public int unfold(UUID updateId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              UpdateEventArchive archive = UpdateEventArchive.findById(updateId);
              if (archive == null) {
                return 0;
              }
              List<StoredEvent> events = codec.decode(archive.codec, archive.events);
              // Clear first: `summary` rows survived the fold and are also inside the blob.
              UpdateEvent.delete("updateId", updateId);
              em.flush();
              for (StoredEvent event : events) {
                UpdateEvent row = new UpdateEvent();
                row.stackId = archive.stackId;
                row.updateId = updateId;
                row.sequence = event.sequence();
                row.kind = event.kind();
                row.severity = event.severity();
                row.payload = event.payload();
                row.persist();
              }
              archive.delete();
              // Back into the pending set: with folding still enabled the sweep will re-fold this,
              // which is why the rollback procedure is to disable folding first.
              StackUpdate.update("eventsFoldedAt = null where id = ?1", updateId);
              em.flush();
              return events.size();
            });
  }
}
