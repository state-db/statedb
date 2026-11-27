package app.statedb.persistence.update;

import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.stack.StackListFinder;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads the cross-stack recent updates rail for the console home and organization screens.
 *
 * <p>Scopes first to the caller's visible stacks ({@link StackListFinder#READABLE_BY_USER_SQL}) and
 * uses per-stack index lookups on {@code idx_updates_in_progress} (active runs) and {@code
 * idx_updates_stack_started_created} (recent history) so latency stays bounded even for small
 * tenants, guests, or rare failure filters without requiring global table-wide scans or extra
 * indexes.
 */
@ApplicationScoped
public class RecentUpdatesFinder {

  private static final int RUNNING_LIMIT = 50;

  @Inject EntityManager em;
  @Inject UpdateActorAttributionFinder actorAttributionFinder;

  public RecentUpdatesResult find(
      UUID userId, String orgName, boolean includePreviews, String statusFilter, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 50));

    List<RawRow> runningRaw =
        "failed".equals(statusFilter)
            ? List.of()
            : queryRows(userId, orgName, includePreviews, true, false, RUNNING_LIMIT);

    List<RawRow> recentRaw =
        "running".equals(statusFilter)
            ? List.of()
            : queryRows(
                userId, orgName, includePreviews, false, "failed".equals(statusFilter), safeLimit);

    if (!runningRaw.isEmpty() && !recentRaw.isEmpty()) {
      java.util.Set<UUID> runningIds = new java.util.HashSet<>(runningRaw.size());
      for (RawRow r : runningRaw) {
        runningIds.add(r.id());
      }
      recentRaw = recentRaw.stream().filter(r -> !runningIds.contains(r.id())).toList();
    }

    List<RawRow> combined = new ArrayList<>(runningRaw.size() + recentRaw.size());
    combined.addAll(runningRaw);
    combined.addAll(recentRaw);

    if (combined.isEmpty()) {
      return new RecentUpdatesResult(List.of(), List.of());
    }

    List<UpdateActorAttributionFinder.ActorRef> actorRefs = new ArrayList<>(combined.size());
    List<UUID> ids = new ArrayList<>(combined.size());
    for (RawRow r : combined) {
      actorRefs.add(
          new UpdateActorAttributionFinder.ActorRef(
              r.id(), r.startedByUserId(), r.startedByApiTokenId()));
      ids.add(r.id());
    }
    Map<UUID, UpdateActorAttribution> actors = actorAttributionFinder.forUpdateRefs(actorRefs);
    Map<UUID, JsonNode> summaries = loadSummaries(ids);

    List<RecentUpdateItem> running = new ArrayList<>(runningRaw.size());
    for (RawRow r : runningRaw) {
      running.add(toItem(r, actors, summaries));
    }
    List<RecentUpdateItem> recent = new ArrayList<>(recentRaw.size());
    for (RawRow r : recentRaw) {
      recent.add(toItem(r, actors, summaries));
    }
    return new RecentUpdatesResult(running, recent);
  }

  private Map<UUID, JsonNode> loadSummaries(List<UUID> ids) {
    Map<UUID, JsonNode> summaries = new HashMap<>();
    List<UpdateEvent> events =
        em.createQuery(
                "from UpdateEvent e where e.kind = :kind and e.updateId in :ids", UpdateEvent.class)
            .setParameter("kind", "summary")
            .setParameter("ids", ids)
            .getResultList();
    for (UpdateEvent event : events) {
      summaries.put(event.updateId, event.payload.path("summaryEvent"));
    }
    return summaries;
  }

  private List<RawRow> queryRows(
      UUID userId,
      String orgName,
      boolean includePreviews,
      boolean runningOnly,
      boolean failedOnly,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            WITH visible_stacks AS (
              SELECT
                s.id AS stack_id,
                o.name AS org_name,
                p.name AS project_name,
                s.name AS stack_name
              FROM stacks s
              JOIN projects p ON p.id = s.project_id
              JOIN organizations o ON o.id = s.org_id
              WHERE (CAST(:orgName AS text) IS NULL OR o.name = :orgName)
                AND
            """);
    sql.append(StackListFinder.READABLE_BY_USER_SQL).append(")\n");

    if (runningOnly) {
      sql.append(
          """
          SELECT
            u.id,
            vs.org_name,
            vs.project_name,
            vs.stack_name,
            u.dry_run,
            u.version,
            u.kind,
            u.status,
            COALESCE(u.message, u.description) AS message,
            u.started_by_user_id,
            u.started_by_api_token_id,
            u.started_at,
            u.ended_at,
            NULLIF(BTRIM(u.metadata->'environment'->>'ci.pr.number'), '') AS pr_number
          FROM visible_stacks vs
          JOIN stack_updates u ON u.stack_id = vs.stack_id
          WHERE u.status = 'in-progress'
          """);
      if (!includePreviews) {
        sql.append(" AND NOT u.dry_run");
      }
      sql.append(" ORDER BY u.started_at DESC, u.created_at DESC, u.id DESC LIMIT :limit");
    } else {
      sql.append(
          """
          SELECT
            u.id,
            vs.org_name,
            vs.project_name,
            vs.stack_name,
            u.dry_run,
            u.version,
            u.kind,
            u.status,
            u.message,
            u.started_by_user_id,
            u.started_by_api_token_id,
            u.started_at,
            u.ended_at,
            u.pr_number
          FROM visible_stacks vs
          CROSS JOIN LATERAL (
            SELECT
              u.id,
              u.dry_run,
              u.version,
              u.kind,
              u.status,
              COALESCE(u.message, u.description) AS message,
              u.started_by_user_id,
              u.started_by_api_token_id,
              u.started_at,
              u.created_at,
              u.ended_at,
              NULLIF(BTRIM(u.metadata->'environment'->>'ci.pr.number'), '') AS pr_number
            FROM stack_updates u
            WHERE u.stack_id = vs.stack_id
          """);
      if (failedOnly) {
        sql.append(" AND u.status = 'failed'");
      } else {
        sql.append(" AND u.status <> 'in-progress'");
      }
      if (!includePreviews) {
        sql.append(" AND NOT u.dry_run");
      }
      sql.append(
          """
            ORDER BY u.started_at DESC, u.created_at DESC, u.id DESC
            LIMIT :limit
          ) u
          ORDER BY u.started_at DESC, u.created_at DESC, u.id DESC
          LIMIT :limit
          """);
    }

    Query query = em.createNativeQuery(sql.toString());
    query.setParameter("userId", userId);
    query.setParameter("orgName", orgName);
    query.setParameter("limit", limit);

    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();
    List<RawRow> results = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      results.add(
          new RawRow(
              toUuid(row[0]),
              (String) row[1],
              (String) row[2],
              (String) row[3],
              Boolean.TRUE.equals(row[4]),
              ((Number) row[5]).intValue(),
              (String) row[6],
              (String) row[7],
              (String) row[8],
              toUuid(row[9]),
              toUuid(row[10]),
              toInstant(row[11]),
              toInstant(row[12]),
              parsePositiveLong((String) row[13])));
    }
    return results;
  }

  private static RecentUpdateItem toItem(
      RawRow r, Map<UUID, UpdateActorAttribution> actors, Map<UUID, JsonNode> summaries) {
    return new RecentUpdateItem(
        r.id(),
        r.org(),
        r.project(),
        r.stack(),
        r.dryRun() ? null : r.version(),
        r.dryRun(),
        r.kind(),
        r.status(),
        r.message(),
        summaries.get(r.id()),
        actors.get(r.id()),
        r.startedAt(),
        r.endedAt(),
        r.prNumber());
  }

  private static UUID toUuid(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof UUID uuid) {
      return uuid;
    }
    return UUID.fromString(value.toString());
  }

  private static Instant toInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.toInstant();
    }
    if (value instanceof Timestamp ts) {
      return ts.toInstant();
    }
    throw new IllegalStateException("Unexpected timestamp type: " + value.getClass().getName());
  }

  private static Long parsePositiveLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private record RawRow(
      UUID id,
      String org,
      String project,
      String stack,
      boolean dryRun,
      int version,
      String kind,
      String status,
      String message,
      UUID startedByUserId,
      UUID startedByApiTokenId,
      Instant startedAt,
      Instant endedAt,
      Long prNumber) {}

  public record RecentUpdateItem(
      UUID id,
      String org,
      String project,
      String stack,
      Integer version,
      boolean dryRun,
      String kind,
      String status,
      String message,
      JsonNode summary,
      UpdateActorAttribution actor,
      Instant startedAt,
      Instant endedAt,
      Long prNumber) {}

  public record RecentUpdatesResult(
      List<RecentUpdateItem> running, List<RecentUpdateItem> recent) {}
}
