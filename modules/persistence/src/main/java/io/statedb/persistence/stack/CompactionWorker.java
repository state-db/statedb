package io.statedb.persistence.stack;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.statedb.persistence.pulumi.PulumiStateJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionWorker {

  private static final Logger LOG = Logger.getLogger(CompactionWorker.class);

  @Inject EntityManager em;

  @Inject StackAssembler assembler;

  @ConfigProperty(name = "state-db.compaction.per-tick-cap", defaultValue = "25")
  int perTickCap;

  @ConfigProperty(name = "state-db.compaction.claim-timeout", defaultValue = "5m")
  Duration claimTimeout;

  @ConfigProperty(name = "state-db.compaction.max-attempts", defaultValue = "3")
  int maxAttempts;

  @Scheduled(every = "{state-db.compaction.poll-interval}")
  void scheduledTick() {
    reclaimStuckJobs();
    drainOnce();
  }

  public int drainOnce() {
    int processed = 0;
    Set<UUID> processedJobs = new HashSet<>();
    while (processed < perTickCap) {
      ClaimedJob job = claimNextJob(processedJobs);
      if (job == null) {
        return processed;
      }
      processedJobs.add(job.id());
      process(job);
      processed++;
    }
    return processed;
  }

  public int reclaimStuckJobs() {
    Instant cutoff = Instant.now().minus(claimTimeout);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              // Bulk reaper transition: avoids hydrating timed-out rows and keeps the status flip
              // atomic across all pods.
              int failed =
                  em.createNativeQuery(
                          """
                          UPDATE compaction_jobs
                             SET status = 'failed',
                                 completed_at = now(),
                                 error_message = COALESCE(error_message, 'compaction claim timed out')
                           WHERE status = 'running'
                             AND started_at < ?1
                             AND attempts >= ?2
                          """)
                      .setParameter(1, cutoff)
                      .setParameter(2, maxAttempts)
                      .executeUpdate();
              int reclaimed =
                  em.createNativeQuery(
                          """
                          UPDATE compaction_jobs
                             SET status = 'pending',
                                 started_at = NULL,
                                 error_message = COALESCE(error_message, 'compaction claim timed out')
                           WHERE status = 'running'
                             AND started_at < ?1
                             AND attempts < ?2
                          """)
                      .setParameter(1, cutoff)
                      .setParameter(2, maxAttempts)
                      .executeUpdate();
              return failed + reclaimed;
            });
  }

  private ClaimedJob claimNextJob(Set<UUID> excludedJobIds) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              String excludedPredicate = excludedJobIds.isEmpty() ? "" : "AND id NOT IN ?1";
              // Postgres queue primitive: SKIP LOCKED + UPDATE RETURNING atomically claims one
              // pending job across competing pods.
              Query query =
                  em.createNativeQuery(
                      ("""
                      WITH next_job AS (
                          SELECT id
                            FROM compaction_jobs
                           WHERE status = 'pending'
                             %s
                           ORDER BY created_at
                           FOR UPDATE SKIP LOCKED
                           LIMIT 1
                      )
                      UPDATE compaction_jobs j
                         SET status = 'running',
                             started_at = now(),
                             attempts = attempts + 1,
                             error_message = NULL
                        FROM next_job
                       WHERE j.id = next_job.id
                      RETURNING j.id, j.stack_id, j.attempts
                      """)
                          .formatted(excludedPredicate));
              if (!excludedJobIds.isEmpty()) {
                query.setParameter(1, excludedJobIds);
              }
              @SuppressWarnings("unchecked")
              List<Object[]> rows = query.getResultList();
              if (rows.isEmpty()) {
                return null;
              }
              Object[] row = rows.getFirst();
              return new ClaimedJob((UUID) row[0], (UUID) row[1], ((Number) row[2]).intValue());
            });
  }

  private void process(ClaimedJob job) {
    try {
      QuarkusTransaction.requiringNew().run(() -> compact(job));
    } catch (RuntimeException e) {
      recordFailure(job, e);
    }
  }

  private void compact(ClaimedJob job) {
    CompactionJob compactionJob = em.find(CompactionJob.class, job.id());
    if (compactionJob == null) {
      return;
    }

    StackState state =
        StackState.find("stackId", job.stackId())
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .<StackState>singleResult();
    long journalSeq = state.journalSeq;
    int snapshotVersion = state.baseSnapshotVersion + 1;

    AssembledDeployment assembled = assembler.assemble(job.stackId());

    Snapshot snapshot = new Snapshot();
    snapshot.stackId = job.stackId();
    snapshot.version = snapshotVersion;
    snapshot.checkpoint = PulumiStateJson.withoutStackTraces(assembled.deployment());
    snapshot.compactedFromJournalSeq = journalSeq;
    snapshot.resourceCount = snapshot.checkpoint.path("resources").size();
    snapshot.persistAndFlush();

    state.baseSnapshotId = snapshot.id;
    state.baseSnapshotVersion = snapshotVersion;
    state.journalEntriesSinceCompaction = 0;

    compactionJob.status = CompactionJob.Status.COMPLETED;
    compactionJob.completedAt = Instant.now();
    compactionJob.errorMessage = null;
  }

  private void recordFailure(ClaimedJob job, RuntimeException error) {
    String message = error.getMessage();
    if (message != null && message.length() > 2_000) {
      message = message.substring(0, 2_000);
    }
    LOG.warnf("stack=%s compaction job=%s failed: %s", job.stackId(), job.id(), message);
    LOG.debug("compaction failure", error);
    String finalMessage = message;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CompactionJob compactionJob = em.find(CompactionJob.class, job.id());
              if (compactionJob == null) {
                return;
              }
              if (job.attempts() >= maxAttempts) {
                compactionJob.status = CompactionJob.Status.FAILED;
                compactionJob.completedAt = Instant.now();
                compactionJob.errorMessage = finalMessage;
              } else {
                compactionJob.status = CompactionJob.Status.PENDING;
                compactionJob.startedAt = null;
                compactionJob.errorMessage = finalMessage;
              }
            });
  }

  private record ClaimedJob(UUID id, UUID stackId, int attempts) {}
}
