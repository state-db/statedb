package app.statedb.persistence.update;

import app.statedb.persistence.update.StackUpdate.Kind;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * Loads a stack's deployment history newest-first, the data behind {@code pulumi stack history}.
 *
 * <p>Excludes dry runs ({@code pulumi preview} and the dry-run pass of up/refresh/destroy — not
 * real deployments) and {@link Kind#RENAME} (recorded for the console's audit trail, but stock
 * Pulumi never surfaces a rename in {@code stack history} — the diy backend relocates history files
 * without re-recording, and the service backend handles rename as a separate op). Dropping them
 * here keeps CLI output byte-compatible with stock Pulumi.
 */
@ApplicationScoped
public class StackHistoryFinder {

  /**
   * Returns history rows for {@code stackId}, newest first. A non-positive {@code page} returns all
   * rows; otherwise rows are paginated with a 1-based {@code page} of size {@code pageSize}.
   */
  public List<StackUpdate> find(UUID stackId, int page, int pageSize) {
    // kind = PREVIEW rows are semantically dry runs even if written by a path that skipped the
    // starter (raw seeds, legacy rows), so both predicates stay.
    PanacheQuery<StackUpdate> query =
        StackUpdate.find(
            "stackId = ?1 and dryRun = false and kind not in ?2 order by version desc",
            stackId,
            List.of(Kind.PREVIEW, Kind.RENAME));
    if (page > 0) {
      query.page(Page.of(page - 1, pageSize));
    }
    return query.list();
  }
}
