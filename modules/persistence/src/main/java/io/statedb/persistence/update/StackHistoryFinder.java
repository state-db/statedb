package io.statedb.persistence.update;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.statedb.persistence.update.StackUpdate.Kind;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * Loads a stack's deployment history newest-first, the data behind {@code pulumi stack history}.
 *
 * <p>Excludes {@link Kind#PREVIEW} (not a real deployment) and {@link Kind#RENAME} (recorded for
 * the console's audit trail, but stock Pulumi never surfaces a rename in {@code stack history} —
 * the diy backend relocates history files without re-recording, and the service backend handles
 * rename as a separate op). Dropping them here keeps CLI output byte-compatible with stock Pulumi.
 */
@ApplicationScoped
public class StackHistoryFinder {

  /**
   * Returns history rows for {@code stackId}, newest first. A non-positive {@code page} returns all
   * rows; otherwise rows are paginated with a 1-based {@code page} of size {@code pageSize}.
   */
  public List<StackUpdate> find(UUID stackId, int page, int pageSize) {
    PanacheQuery<StackUpdate> query =
        StackUpdate.find(
            "stackId = ?1 and kind not in ?2 order by version desc",
            stackId,
            List.of(Kind.PREVIEW, Kind.RENAME));
    if (page > 0) {
      query.page(Page.of(page - 1, pageSize));
    }
    return query.list();
  }
}
