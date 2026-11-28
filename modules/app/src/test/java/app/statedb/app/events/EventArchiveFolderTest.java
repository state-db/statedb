package app.statedb.app.events;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.events.EventArchiveFolder;
import app.statedb.persistence.events.StoredEvent;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.events.UpdateEventArchive;
import app.statedb.persistence.events.UpdateEventReader;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Folding, its eligibility rules, and unfolding. Uses the recorded engine-event corpus so the
 * measured compression ratio reflects real Pulumi output rather than synthetic filler.
 *
 * <p>Assertions are made about specific updates rather than {@code foldOnce()}'s return count: the
 * sweep is global, and other tests sharing this database may leave foldable updates behind.
 */
@QuarkusTest
class EventArchiveFolderTest extends DatabaseTest {

  private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

  @Inject EventArchiveFolder folder;
  @Inject UpdateEventReader reader;

  private UUID currentStackId;

  @Test
  void foldsTerminalUpdateKeepingSummaryHotAndUnfoldsBackExactly() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("evt-archive-fold");
    UUID updateId =
        seedUpdate(stackId, 1, StackUpdate.Status.SUCCEEDED, LONG_AGO, corpus, corpus.size());

    List<StoredEvent> before = events(stackId, updateId);
    assertEquals(corpus.size(), before.size());
    long summaryRows = summaryRowCount(updateId);
    assertTrue(summaryRows > 0, "recorded corpus should contain a summary event");
    long payloadBytes = payloadBytes(updateId);

    folder.foldOnce();

    UpdateEventArchive archive = archive(updateId);
    assertNotNull(archive, "archive written");
    assertEquals(corpus.size(), archive.eventCount);
    assertEquals(corpus.size() - 1, archive.maxSequence);
    assertEquals(stackId, archive.stackId);

    // Only `summary` rows survive as rows; everything else now lives in the blob.
    assertEquals(summaryRows, rowCount(updateId), "only summary rows remain as rows");
    assertEquals(summaryRows, summaryRowCount(updateId));

    // Guards the codec choice, not merely that some compression happened. Measured on this corpus:
    // zstd reaches 35.8x against what Postgres stores per row, gzip roughly 19x (it is ~2x larger
    // both here and on the soak sample). 25x therefore fails if the codec regresses to gzip.
    assertTrue(
        archive.events.length * 25L <= payloadBytes,
        "blob %d bytes vs %d bytes of row payloads (%.1fx)"
            .formatted(
                archive.events.length,
                payloadBytes,
                payloadBytes / (double) archive.events.length));

    // Reads are transparent: the same events, now flagged as archived.
    UpdateEventReader.EventPage page =
        requiringNew().call(() -> reader.page(stackId, updateId, -1, 10_000));
    assertTrue(page.archived());
    assertEquals(before, page.events());

    // Re-running is a no-op rather than a duplicate or a loss.
    folder.foldOnce();
    assertEquals(corpus.size(), archive(updateId).eventCount);
    assertEquals(summaryRows, rowCount(updateId));

    // Unfolding restores every row exactly and removes the archive.
    assertEquals(corpus.size(), folder.unfold(updateId));
    assertNull(archive(updateId));
    UpdateEventReader.EventPage restored =
        requiringNew().call(() -> reader.page(stackId, updateId, -1, 10_000));
    assertFalse(restored.archived());
    assertEquals(before, restored.events());
  }

  /**
   * The archived read path skips a prefix of the blob rather than decoding it, so every page
   * boundary is a chance for it to drop or duplicate an event. Walking the whole stream in pages
   * before and after folding pins that against the row-backed answer.
   */
  @Test
  void archivedPagingMatchesRowPagingAtEveryBoundary() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("evt-archive-paging");
    UUID updateId = seedUpdate(stackId, 1, StackUpdate.Status.SUCCEEDED, LONG_AGO, corpus, 1_200);

    List<List<StoredEvent>> fromRows = walk(stackId, updateId, 250);
    assertEquals(5, fromRows.size(), "1200 events at 250 per page");
    // A cursor landing mid-page, and one past the end of the stream.
    List<StoredEvent> midPageFromRows =
        requiringNew().call(() -> reader.page(stackId, updateId, 617, 250).events());
    assertEquals(618, midPageFromRows.getFirst().sequence());

    requiringNew().run(() -> folder.fold(updateId, Instant.now()));

    assertEquals(fromRows, walk(stackId, updateId, 250));
    assertEquals(
        midPageFromRows,
        requiringNew().call(() -> reader.page(stackId, updateId, 617, 250).events()));
    assertTrue(
        requiringNew().call(() -> reader.page(stackId, updateId, 1_199, 250).events()).isEmpty());
  }

  /**
   * Rows and an archive coexisting is the state an unfold passes through. The reader must treat the
   * archive as the sole authority there and never mix the two or return the row set, because the
   * rows it would return are the summary-only remnant — or, for a stream with no summary event at
   * all, nothing, which the endpoint would report as an expired log.
   */
  @Test
  void archivePresentWinsOverRowsThatStillExist() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("evt-archive-authority");
    UUID updateId = seedUpdate(stackId, 1, StackUpdate.Status.SUCCEEDED, LONG_AGO, corpus, 40);
    List<StoredEvent> full = events(stackId, updateId);

    // Fold, then put the rows back without removing the archive: both sources now hold the stream.
    requiringNew().run(() -> folder.fold(updateId, Instant.now()));
    UpdateEventArchive archive = archive(updateId);
    requiringNew()
        .run(
            () -> {
              UpdateEvent.delete("updateId", updateId);
              EventArchiveFixture.persistEvents(stackId, updateId, corpus, 40);
            });
    assertEquals(40L, rowCount(updateId), "rows and archive coexist");
    assertNotNull(archive(updateId));

    UpdateEventReader.EventPage page =
        requiringNew().call(() -> reader.page(stackId, updateId, -1, 10_000));
    assertTrue(page.archived(), "archive is authoritative while both exist");
    assertEquals(full, page.events());
    assertEquals(archive.eventCount, page.events().size(), "no rows mixed into the blob's answer");
  }

  private List<List<StoredEvent>> walk(UUID stackId, UUID updateId, int limit) {
    List<List<StoredEvent>> pages = new java.util.ArrayList<>();
    int after = -1;
    while (true) {
      int cursor = after;
      List<StoredEvent> page =
          requiringNew().call(() -> reader.page(stackId, updateId, cursor, limit).events());
      pages.add(page);
      if (page.size() < limit) {
        return pages;
      }
      after = page.getLast().sequence();
    }
  }

  /**
   * The sweep must cost what is outstanding, not what has ever run. An update the folder has
   * settled leaves the pending set for good — including one it settled by finding nothing to fold,
   * which is the case a "has no archive yet" predicate can never retire and would rescan forever.
   */
  @Test
  void settledUpdatesLeaveThePendingSetIncludingOnesWithNothingToFold() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("evt-archive-drain");
    UUID withEvents =
        seedUpdate(stackId, 1, StackUpdate.Status.SUCCEEDED, LONG_AGO, corpus, corpus.size());
    // An import or a run that ended before its first event batch: terminal, but nothing to fold.
    UUID withoutEvents = seedUpdate(stackId, 2, StackUpdate.Status.SUCCEEDED, LONG_AGO, corpus, 0);

    assertTrue(pending().containsAll(List.of(withEvents, withoutEvents)), "both start pending");

    folder.foldOnce();

    assertNotNull(archive(withEvents), "the update with events was folded");
    assertNull(archive(withoutEvents), "an update with nothing to fold gets no archive");
    assertTrue(pending().isEmpty(), "the pending set drained");

    // A second sweep finds nothing of this stack's left to reconsider, and changes nothing.
    int eventCount = archive(withEvents).eventCount;
    folder.foldOnce();
    assertTrue(pending().isEmpty());
    assertEquals(eventCount, archive(withEvents).eventCount);
    assertNull(archive(withoutEvents));
  }

  /** Unfolding is a rollback, so it puts the update back where the sweep can find it. */
  @Test
  void unfoldingReturnsTheUpdateToThePendingSet() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("evt-archive-unfold-pending");
    UUID updateId = seedUpdate(stackId, 1, StackUpdate.Status.SUCCEEDED, LONG_AGO, corpus, 40);

    requiringNew().run(() -> folder.fold(updateId, Instant.now()));
    assertTrue(pending().isEmpty(), "folding settles it");

    folder.unfold(updateId);

    assertTrue(pending().contains(updateId), "unfolding makes it foldable again");
  }

  /** The updates this stack's sweep would consider on the next tick. */
  @SuppressWarnings("unchecked")
  private List<UUID> pending() {
    return em.createNativeQuery(
            "SELECT id FROM stack_updates WHERE events_folded_at IS NULL"
                + " AND status <> 'in-progress' AND stack_id = ?1")
        .setParameter(1, currentStackId)
        .getResultList();
  }

  @Test
  void neverFoldsInProgressOrInGraceUpdates() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("evt-archive-ineligible");
    UUID inProgress = seedUpdate(stackId, 1, StackUpdate.Status.IN_PROGRESS, null, corpus, 20);
    UUID inGrace =
        seedUpdate(
            stackId,
            2,
            StackUpdate.Status.FAILED,
            Instant.now().minus(Duration.ofMinutes(1)),
            corpus,
            20);

    folder.foldOnce();

    assertNull(archive(inProgress), "in-progress update must never be folded");
    assertNull(archive(inGrace), "update still inside the grace period must never be folded");
    assertEquals(20L, rowCount(inProgress));
    assertEquals(20L, rowCount(inGrace));
  }

  private List<StoredEvent> events(UUID stackId, UUID updateId) {
    return requiringNew().call(() -> reader.page(stackId, updateId, -1, 10_000).events());
  }

  private UpdateEventArchive archive(UUID updateId) {
    return requiringNew().call(() -> UpdateEventArchive.<UpdateEventArchive>findById(updateId));
  }

  private long rowCount(UUID updateId) {
    return UpdateEvent.count("updateId", updateId);
  }

  private long summaryRowCount(UUID updateId) {
    return UpdateEvent.count("updateId = ?1 and kind = ?2", updateId, UpdateEvent.SUMMARY_KIND);
  }

  private long payloadBytes(UUID updateId) {
    // pg_column_size is the point of the measurement: it reports what Postgres actually stores,
    // TOAST compression included, which is the baseline the blob has to beat.
    return ((Number)
            em.createNativeQuery(
                    "SELECT sum(pg_column_size(payload)) FROM update_events"
                        + " WHERE update_id = ?1")
                .setParameter(1, updateId)
                .getSingleResult())
        .longValue();
  }

  private UUID seedStack(String orgName) {
    currentStackId = db.seedStack(orgName, "project", "dev").stackId();
    return currentStackId;
  }

  private UUID seedUpdate(
      UUID stackId,
      int version,
      StackUpdate.Status status,
      Instant endedAt,
      List<JsonNode> corpus,
      int eventCount) {
    return requiringNew()
        .call(
            () -> {
              StackUpdate update = new StackUpdate();
              update.stackId = stackId;
              update.kind = StackUpdate.Kind.UPDATE;
              update.status = status;
              update.version = version;
              update.startedAt = LONG_AGO;
              update.endedAt = endedAt;
              update.persistAndFlush();
              EventArchiveFixture.persistEvents(stackId, update.id, corpus, eventCount);
              em.flush();
              return update.id;
            });
  }
}
