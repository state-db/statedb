package app.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregates visible stacks into project-level directory nodes (grouped by VCS repository and
 * folder root) plus org/global totals in one SQL pass.
 *
 * <p>Uses native SQL to avoid hydrating every visible {@link Stack}, {@link
 * app.statedb.persistence.update.StackUpdate}, and {@link Snapshot} row on the console home page.
 * The visibility predicate mirrors {@link StackListFinder}'s Cedar-equivalent SQL read filter.
 */
@ApplicationScoped
public class WorkspaceDirectoryFinder {

  @Inject EntityManager em;

  public DirectoryResult find(UUID userId, String orgName) {
    String sql =
        """
        WITH visible_stacks AS (
          SELECT
            s.id AS stack_id,
            o.name AS org_name,
            p.id AS project_id,
            p.name AS project_name,
            p.created_at AS project_created_at,
            COALESCE(sn.resource_count, 0) AS resource_count,
            (l.stack_id IS NOT NULL OR lu.status = 'in-progress') AS is_running,
            (la.status = 'failed') AS is_failed,
            COALESCE(lu.started_at, s.updated_at) AS last_activity_at,
            CASE
              WHEN NULLIF(BTRIM(s.tags->>'vcs:owner'), '') IS NOT NULL
                AND NULLIF(BTRIM(s.tags->>'vcs:repo'), '') IS NOT NULL
                THEN NULLIF(BTRIM(s.tags->>'vcs:kind'), '')
              WHEN NULLIF(BTRIM(s.tags->>'gitHub:owner'), '') IS NOT NULL
                AND NULLIF(BTRIM(s.tags->>'gitHub:repo'), '') IS NOT NULL
                THEN 'github.com'
              ELSE NULL
            END AS vcs_kind,
            COALESCE(
              NULLIF(BTRIM(s.tags->>'vcs:owner'), ''),
              NULLIF(BTRIM(s.tags->>'gitHub:owner'), '')
            ) AS vcs_owner,
            COALESCE(
              NULLIF(BTRIM(s.tags->>'vcs:repo'), ''),
              NULLIF(BTRIM(s.tags->>'gitHub:repo'), '')
            ) AS vcs_repo,
            NULLIF(BTRIM(s.tags->>'vcs:root'), '') AS vcs_root
          FROM stacks s
          JOIN projects p ON p.id = s.project_id
          JOIN organizations o ON o.id = s.org_id
          LEFT JOIN stack_state ss ON ss.stack_id = s.id
          LEFT JOIN snapshots sn ON sn.id = ss.base_snapshot_id
          LEFT JOIN stack_locks l ON l.stack_id = s.id AND l.expires_at > now()
          LEFT JOIN LATERAL (
            SELECT u.status, u.started_at
            FROM stack_updates u
            WHERE u.stack_id = s.id
            ORDER BY u.started_at DESC, u.created_at DESC
            LIMIT 1
          ) lu ON TRUE
          LEFT JOIN LATERAL (
            SELECT u.status
            FROM stack_updates u
            WHERE u.stack_id = s.id AND NOT u.dry_run
            ORDER BY u.version DESC
            LIMIT 1
          ) la ON TRUE
          WHERE (CAST(:orgName AS text) IS NULL OR o.name = :orgName)
            AND
        """
            + StackListFinder.READABLE_BY_USER_SQL
            + """
        ),
        project_vcs AS (
          SELECT DISTINCT ON (project_id)
            project_id,
            vcs_kind,
            vcs_owner,
            vcs_repo,
            vcs_root
          FROM visible_stacks
          WHERE vcs_owner IS NOT NULL AND vcs_repo IS NOT NULL
          ORDER BY project_id, last_activity_at DESC, stack_id DESC
        )
        SELECT
          vs.org_name,
          vs.project_name,
          vs.project_created_at,
          pv.vcs_kind,
          pv.vcs_owner,
          pv.vcs_repo,
          pv.vcs_root,
          COUNT(*)::bigint AS stack_count,
          COALESCE(SUM(vs.resource_count), 0)::bigint AS resource_count,
          COUNT(*) FILTER (WHERE vs.is_running)::bigint AS running_count,
          COUNT(*) FILTER (WHERE vs.is_failed)::bigint AS failed_count,
          MAX(vs.last_activity_at) AS last_activity_at
        FROM visible_stacks vs
        LEFT JOIN project_vcs pv ON pv.project_id = vs.project_id
        GROUP BY
          vs.org_name,
          vs.project_id,
          vs.project_name,
          vs.project_created_at,
          pv.vcs_kind,
          pv.vcs_owner,
          pv.vcs_repo,
          pv.vcs_root
        ORDER BY
          pv.vcs_owner NULLS LAST,
          pv.vcs_repo NULLS LAST,
          pv.vcs_root NULLS FIRST,
          vs.org_name,
          vs.project_name
        """;

    Query query = em.createNativeQuery(sql);
    query.setParameter("userId", userId);
    query.setParameter("orgName", orgName);

    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();

    List<ProjectDirectoryEntry> entries = new ArrayList<>(rows.size());
    long totalStacks = 0;
    long totalResources = 0;
    long totalRunning = 0;
    long totalFailed = 0;

    for (Object[] row : rows) {
      String org = (String) row[0];
      String project = (String) row[1];
      Instant projectCreatedAt = toInstant(row[2]);
      String vcsKind = (String) row[3];
      String vcsOwner = (String) row[4];
      String vcsRepo = (String) row[5];
      String vcsRoot = normalizeVcsRoot((String) row[6]);
      long stackCount = ((Number) row[7]).longValue();
      long resourceCount = ((Number) row[8]).longValue();
      long runningCount = ((Number) row[9]).longValue();
      long failedCount = ((Number) row[10]).longValue();
      Instant lastActivityAt = toInstant(row[11]);

      totalStacks += stackCount;
      totalResources += resourceCount;
      totalRunning += runningCount;
      totalFailed += failedCount;

      entries.add(
          new ProjectDirectoryEntry(
              org,
              project,
              projectCreatedAt,
              vcsKind,
              vcsOwner,
              vcsRepo,
              vcsRoot,
              stackCount,
              resourceCount,
              runningCount,
              failedCount,
              lastActivityAt));
    }

    WorkspaceListFinder.WorkspaceOverview overview =
        new WorkspaceListFinder.WorkspaceOverview(
            totalStacks, entries.size(), totalResources, totalFailed, totalRunning);
    return new DirectoryResult(entries, overview);
  }

  public boolean isOrganizationVisible(UUID userId, String orgName) {
    Query query =
        em.createNativeQuery(
            """
            SELECT 1
            FROM organizations o
            JOIN org_members m ON m.org_id = o.id
            WHERE o.name = :orgName AND m.user_id = :userId
            LIMIT 1
            """);
    query.setParameter("orgName", orgName);
    query.setParameter("userId", userId);
    return !query.getResultList().isEmpty();
  }

  static String normalizeVcsRoot(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim().replace('\\', '/');
    while (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isEmpty() || ".".equals(normalized)) {
      return null;
    }
    return normalized;
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

  public record ProjectDirectoryEntry(
      String org,
      String project,
      Instant projectCreatedAt,
      String vcsKind,
      String vcsOwner,
      String vcsRepo,
      String vcsRoot,
      long stackCount,
      long resourceCount,
      long runningCount,
      long failedCount,
      Instant lastActivityAt) {}

  public record DirectoryResult(
      List<ProjectDirectoryEntry> projects, WorkspaceListFinder.WorkspaceOverview overview) {}
}
