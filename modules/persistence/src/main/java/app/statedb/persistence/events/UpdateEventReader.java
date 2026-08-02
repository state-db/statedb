package app.statedb.persistence.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads one page of an update's event stream, from {@code update_events} rows or from the update's
 * {@link UpdateEventArchive}, whichever holds the stream.
 *
 * <p>An archive's presence is the authority, not whether rows came back: {@link EventArchiveFolder}
 * leaves {@code summary} rows behind, so an archived update still has rows and keying off row
 * emptiness would silently serve a one-event stream.
 *
 * <p><b>Both sources must be read in one snapshot.</b> Under {@code READ COMMITTED} two separate
 * statements get two snapshots, and folding or unfolding committing between them is then visible
 * half-applied: read the rows first and an unfold slipping in returns the pre-unfold summary-only
 * rows; read the archive first and a fold slipping in does the same. When the update has no {@code
 * summary} event at all the truncated page is empty, which the endpoint reports as {@code
 * expired=true} — the console tells the user the log is gone while every event is intact. No
 * ordering of two statements avoids both, because folding removes rows and unfolding adds them.
 *
 * <p>So the read is one statement. The archive branch and the row branch of the {@code UNION ALL}
 * evaluate against the same snapshot, and the row branch's {@code NOT EXISTS} makes them mutually
 * exclusive within it, so exactly one source is ever authoritative. This is why the query is native
 * rather than Panache: the correctness property is the shared snapshot, which two finder calls
 * cannot express.
 */
@ApplicationScoped
public class UpdateEventReader {

  private static final String PAGE_SQL =
      """
      SELECT a.codec, a.events, NULL::int, NULL::text, NULL::text, NULL::jsonb
        FROM update_event_archives a
       WHERE a.update_id = :updateId AND a.stack_id = :stackId
      UNION ALL
      SELECT NULL::text, NULL::bytea, r.sequence, r.kind, r.severity, r.payload
        FROM (SELECT e.sequence, e.kind, e.severity, e.payload
                FROM update_events e
               WHERE e.update_id = :updateId
                 AND e.stack_id = :stackId
                 AND e.sequence > :after
                 AND NOT EXISTS (SELECT 1 FROM update_event_archives a2
                                  WHERE a2.update_id = :updateId AND a2.stack_id = :stackId)
               ORDER BY e.sequence
               LIMIT :limit) r
       ORDER BY 3 NULLS FIRST
      """;

  @Inject EntityManager em;

  @Inject EventArchiveCodec codec;

  @Inject ObjectMapper mapper;

  /**
   * Events with {@code sequence > after}, ascending, at most {@code limit} of them. Slicing the
   * decoded blob relies on {@link EventArchiveFolder} having encoded it in sequence order.
   */
  public EventPage page(UUID stackId, UUID updateId, int after, int limit) {
    Query query =
        em.createNativeQuery(PAGE_SQL)
            .setParameter("updateId", updateId)
            .setParameter("stackId", stackId)
            .setParameter("after", after)
            .setParameter("limit", limit);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();

    // The archive row sorts first on a NULL sequence, and its presence means the row branch was
    // empty; so a leading blob is the whole answer.
    if (!rows.isEmpty() && rows.getFirst()[0] != null) {
      Object[] archive = rows.getFirst();
      return new EventPage(
          codec.decodePage((String) archive[0], (byte[]) archive[1], after, limit), true);
    }

    List<StoredEvent> events = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      events.add(
          new StoredEvent(
              ((Number) row[2]).intValue(), (String) row[3], (String) row[4], payload(row[5])));
    }
    return new EventPage(events, false);
  }

  private JsonNode payload(Object value) {
    try {
      return mapper.readTree((String) value);
    } catch (IOException e) {
      throw new UncheckedIOException("unreadable event payload", e);
    }
  }

  /**
   * One page of events plus which source answered. Callers serve both identically — that is the
   * point of folding — so {@code archived} exists to assert on: it is how a test tells a fold that
   * worked from one that silently served the summary rows the fold left behind.
   */
  public record EventPage(List<StoredEvent> events, boolean archived) {}
}
