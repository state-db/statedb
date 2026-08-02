package app.statedb.persistence.operations;

import app.statedb.persistence.stack.CompactionJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Site-wide operational projections for the admin console.
 *
 * <p>These reads deliberately use constructor HQL: each row crosses the stack tree and returning
 * scalar projections avoids hydrating large update histories or stack state. The two compaction
 * queries preserve the queue's useful ordering: every active job first, followed by a bounded
 * recent terminal history.
 */
@ApplicationScoped
public class OperationalInventoryFinder {

  private static final int TERMINAL_JOB_LIMIT = 50;

  @Inject EntityManager em;

  public List<ActiveLockView> activeLocks(Instant now) {
    return em.createQuery(
            """
            SELECT new app.statedb.persistence.operations.ActiveLockView(
              l.id, s.id, o.name, p.name, s.name, l.lockedBy,
              holder.id, holder.iss, holder.displayName, holder.email,
              l.lockedAt, l.heartbeatAt, l.expiresAt,
              u.id, u.kind, u.version, u.dryRun)
              FROM StackLock l
              JOIN Stack s ON s.id = l.stackId
              JOIN Project p ON p.id = s.projectId
              JOIN Organization o ON o.id = s.orgId
              JOIN StackUpdate u ON u.id = l.updateId
              LEFT JOIN User holder ON holder.id = u.startedByUserId
             WHERE l.expiresAt > :now
             ORDER BY l.lockedAt
            """,
            ActiveLockView.class)
        .setParameter("now", now)
        .getResultList();
  }

  public List<CompactionJobView> recentCompactionJobs() {
    List<CompactionJobView> jobs = new ArrayList<>();
    jobs.addAll(compactionJobs(true, 0));
    jobs.addAll(compactionJobs(false, TERMINAL_JOB_LIMIT));
    return jobs;
  }

  private List<CompactionJobView> compactionJobs(boolean active, int limit) {
    List<CompactionJob.Status> statuses =
        active
            ? List.of(CompactionJob.Status.PENDING, CompactionJob.Status.RUNNING)
            : List.of(CompactionJob.Status.COMPLETED, CompactionJob.Status.FAILED);
    String ordering = active ? "j.createdAt" : "j.createdAt DESC";
    var query =
        em.createQuery(
                ("""
                SELECT new app.statedb.persistence.operations.CompactionJobView(
                  j.id, s.id, o.name, p.name, s.name, j.status, j.attempts,
                  j.errorMessage, j.createdAt, j.startedAt, j.completedAt)
                  FROM CompactionJob j
                  JOIN Stack s ON s.id = j.stackId
                  JOIN Project p ON p.id = s.projectId
                  JOIN Organization o ON o.id = s.orgId
                 WHERE j.status IN :statuses
                 ORDER BY %s
                """)
                    .formatted(ordering),
                CompactionJobView.class)
            .setParameter("statuses", statuses);
    if (limit > 0) {
      query.setMaxResults(limit);
    }
    return query.getResultList();
  }
}
