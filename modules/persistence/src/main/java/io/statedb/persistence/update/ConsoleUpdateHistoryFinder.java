package io.statedb.persistence.update;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.statedb.persistence.events.UpdateEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Loads the console audit history, including previews and their summary events. */
@ApplicationScoped
public class ConsoleUpdateHistoryFinder {

  public Optional<StackUpdate> findUpdate(UUID stackId, int version) {
    return StackUpdate.<StackUpdate>find(
            "stackId = ?1 and version = ?2 and dryRun = false", stackId, version)
        .firstResultOptional();
  }

  public Optional<StackUpdate> findPreview(UUID stackId, UUID updateId) {
    return StackUpdate.<StackUpdate>find(
            "stackId = ?1 and id = ?2 and dryRun = true", stackId, updateId)
        .firstResultOptional();
  }

  public HistoryPage find(
      UUID stackId, StackUpdate.Kind kind, StackUpdate.Status status, int page, int pageSize) {
    StringBuilder predicate = new StringBuilder("stackId = :stackId");
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("stackId", stackId);
    if (kind != null) {
      predicate.append(" and kind = :kind");
      parameters.put("kind", kind);
    }
    if (status != null) {
      predicate.append(" and status = :status");
      parameters.put("status", status);
    }

    // Named parameters keep the optional predicates readable; this is still an entity-owned
    // Panache query and deliberately includes previews and renames for the console audit trail.
    PanacheQuery<StackUpdate> query =
        StackUpdate.find(predicate + " order by startedAt desc, createdAt desc", parameters);
    long total = query.count();
    List<StackUpdate> updates = query.page(Page.of(page - 1, pageSize)).list();

    Map<UUID, JsonNode> summaries = new HashMap<>();
    if (!updates.isEmpty()) {
      List<UUID> ids = updates.stream().map(update -> update.id).toList();
      UpdateEvent.<UpdateEvent>list("kind = ?1 and updateId in ?2", "summary", ids)
          .forEach(event -> summaries.put(event.updateId, event.payload.path("summaryEvent")));
    }
    return new HistoryPage(updates, summaries, total);
  }

  public record HistoryPage(List<StackUpdate> updates, Map<UUID, JsonNode> summaries, long total) {}
}
