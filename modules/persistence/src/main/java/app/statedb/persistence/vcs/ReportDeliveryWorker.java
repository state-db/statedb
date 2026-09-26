package app.statedb.persistence.vcs;

import app.statedb.persistence.AdvisoryLock;
import app.statedb.persistence.events.UpdateEventReader;
import app.statedb.persistence.report.ChangeReportDeriver;
import app.statedb.persistence.report.MarkdownReportRenderer;
import app.statedb.persistence.report.ReportLimits;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRefResolver;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.vcs.ReportPublisher.PullRequestTarget;
import app.statedb.persistence.vcs.ReportPublisher.Repository;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/** Owns enqueue, bounded polling, retries and retention. Database locks are the only claims. */
@ApplicationScoped
public class ReportDeliveryWorker {

  private static final Logger LOG = Logger.getLogger(ReportDeliveryWorker.class);
  private static final int QUEUE_METRIC_CAP = 10_000;

  @Inject EntityManager em;
  @Inject Instance<ReportPublisher> publishers;
  @Inject StackRefResolver stackRefResolver;
  @Inject UpdateEventReader eventReader;
  @Inject ChangeReportDeriver deriver;
  @Inject MarkdownReportRenderer renderer;
  @Inject MeterRegistry metrics;

  @ConfigProperty(name = "statedb.reports.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "statedb.reports.per-tick-cap", defaultValue = "50")
  int perTickCap;

  @ConfigProperty(name = "statedb.reports.max-attempts", defaultValue = "5")
  int maxAttempts;

  @ConfigProperty(name = "statedb.reports.max-age", defaultValue = "7d")
  Duration maxAge;

  @ConfigProperty(name = "statedb.reports.retention", defaultValue = "30d")
  Duration retention;

  @Inject VcsPublicUrl publicUrl;

  private final AtomicInteger queueDepth = new AtomicInteger();

  @PostConstruct
  void registerMetrics() {
    if (perTickCap < 1
        || maxAttempts < 1
        || maxAge.isNegative()
        || maxAge.isZero()
        || retention.isNegative()
        || retention.isZero()) {
      throw new IllegalArgumentException("report queue limits and durations must be positive");
    }
    metrics.gauge("statedb.reports.queue.depth", queueDepth);
  }

  /** Called in the transaction that ends a run; the tags are the ones refreshed at lease time. */
  public void enqueue(StackUpdate update) {
    if (!enabled || update.tags == null) {
      return;
    }
    Map<?, ?> env =
        update.metadata != null && update.metadata.get("environment") instanceof Map<?, ?> map
            ? map
            : Map.of();
    String host = text(update.tags.get("vcs:kind"));
    String owner = text(update.tags.get("vcs:owner"));
    String repo = text(update.tags.get("vcs:repo"));
    String head = sha(env.get("ci.pr.headSHA"));
    if (head == null) {
      head = sha(env.get("git.head"));
    }
    if (host == null || owner == null || repo == null || head == null) {
      return;
    }
    long pr;
    try {
      pr = Math.max(0, Long.parseLong(String.valueOf(env.get("ci.pr.number"))));
    } catch (NumberFormatException ignored) {
      pr = 0;
    }
    // One native statement so an optional add-on cannot fail the run's commit: the join skips
    // orgs without a connection and ON CONFLICT absorbs a repeated enqueue.
    em.createNativeQuery(
            "INSERT INTO report_deliveries (id, update_id, stack_id, connection_id,"
                + " repository_owner, repository_name, head_sha, pr_number)"
                + " SELECT gen_random_uuid(), ?1, s.id, c.id, ?2, ?3, ?4, NULLIF(?5, 0)"
                + " FROM stacks s JOIN vcs_connections c"
                + " ON c.org_id = s.org_id AND c.host = ?6 AND c.enabled"
                + " WHERE s.id = ?7"
                + " ON CONFLICT (update_id) DO NOTHING")
        .setParameter(1, update.id)
        .setParameter(2, owner)
        .setParameter(3, repo)
        .setParameter(4, head)
        .setParameter(5, pr)
        .setParameter(6, host.toLowerCase(java.util.Locale.ROOT))
        .setParameter(7, update.stackId)
        .executeUpdate();
  }

  @Scheduled(
      every = "{statedb.reports.poll-interval}",
      concurrentExecution = ConcurrentExecution.SKIP)
  void scheduledTick() {
    deliverOnce();
  }

  public int deliverOnce() {
    // Retention also runs while publication is disabled.
    QuarkusTransaction.requiringNew().run(this::prune);
    if (!enabled) {
      return 0;
    }
    int delivered = 0;
    for (UUID id : candidates()) {
      try {
        // Commit verified coordinates and rendered body before issuing a remote write. A changed
        // PR head, closed PR or expired event archive must not prevent crash reconciliation.
        boolean ready = QuarkusTransaction.requiringNew().call(() -> attempt(id, this::prepare));
        if (ready
            && QuarkusTransaction.requiringNew()
                .timeout(90)
                .call(() -> attempt(id, this::publish))) {
          delivered++;
        }
      } catch (RuntimeException failure) {
        // Database/transaction failure: the row lock rolls back, so the next tick can recover.
        LOG.errorf(failure, "report delivery=%s transaction failed; pending work will recover", id);
      }
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                queueDepth.set(
                    ((Number)
                            em.createNativeQuery(
                                    "SELECT count(*) FROM (SELECT 1 FROM report_deliveries WHERE"
                                        + " state = 'pending' LIMIT ?1) q")
                                .setParameter(1, QUEUE_METRIC_CAP)
                                .getSingleResult())
                        .intValue()));
    return delivered;
  }

  @SuppressWarnings("unchecked")
  private List<UUID> candidates() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                em.createNativeQuery(
                        """
                        SELECT id FROM report_deliveries
                        WHERE state = 'pending' AND next_attempt_at <= now()
                        ORDER BY next_attempt_at, id LIMIT ?1
                        """)
                    .setParameter(1, perTickCap)
                    .getResultList());
  }

  /** One transaction's work on a delivery whose row lock and publisher are already held. */
  private interface Step {
    boolean run(ReportDelivery d, ReportPublisher publisher, Repository repository);
  }

  /** Runs a step on a still-due delivery and records its retry or terminal failure. */
  private boolean attempt(UUID id, Step step) {
    // Native SQL is needed for SKIP LOCKED. No lease timestamps or claimed state survive a crash.
    if (em.createNativeQuery(
            """
            SELECT id FROM report_deliveries
            WHERE id = ?1 AND state = 'pending' AND next_attempt_at <= now()
            FOR UPDATE SKIP LOCKED
            """)
        .setParameter(1, id)
        .getResultList()
        .isEmpty()) {
      return false;
    }
    ReportDelivery d = ReportDelivery.findById(id);
    MDC.put("delivery_id", d.id);
    MDC.put("update_id", d.updateId);
    MDC.put("stack_id", d.stackId);
    MDC.put("pr_number", d.targetPrNumber == null ? String.valueOf(d.prNumber) : d.targetPrNumber);
    try {
      if (d.createdAt.isBefore(Instant.now().minus(maxAge))) {
        return fail(d, "report exceeded maximum queue age");
      }
      VcsConnection connection = VcsConnection.findById(d.connectionId);
      if (connection == null || !connection.enabled) {
        return fail(d, "connection is disabled or deleted");
      }
      ReportPublisher publisher =
          publishers.stream()
              .filter(p -> p.supports(connection.kind, connection.host))
              .findFirst()
              .orElse(null);
      if (publisher == null) {
        return fail(d, "no publisher for connection");
      }
      Repository repository =
          new Repository(
              connection.id,
              connection.host,
              d.targetRepositoryId,
              d.repositoryOwner,
              d.repositoryName);
      return step.run(d, publisher, repository);
    } catch (RuntimeException failure) {
      String message =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      d.lastError = message.substring(0, Math.min(1000, message.length()));
      if ((failure instanceof ReportPublisher.Failure remote && !remote.retryable)
          || d.attempts >= maxAttempts) {
        return fail(d, d.lastError);
      }
      long delay = failure instanceof ReportPublisher.Failure remote ? remote.retryAfterSeconds : 0;
      d.nextAttemptAt =
          Instant.now().plusSeconds(Math.max(delay, Math.min(300, 1L << Math.min(d.attempts, 8))));
      Instant expiry = d.createdAt.plus(maxAge);
      if (d.nextAttemptAt.isAfter(expiry)) {
        d.nextAttemptAt = expiry;
      }
      LOG.warnf(
          "report retry attempts=%d next_attempt_at=%s reason=%s",
          d.attempts, d.nextAttemptAt, d.lastError);
      return false;
    } finally {
      MDC.remove("delivery_id");
      MDC.remove("update_id");
      MDC.remove("stack_id");
      MDC.remove("pr_number");
    }
  }

  /** Verifies the PR target and freezes the rendered body before any remote write. */
  private boolean prepare(ReportDelivery d, ReportPublisher publisher, Repository repository) {
    if (d.body != null) {
      return true;
    }
    StackUpdate update = StackUpdate.findById(d.updateId);
    StackRef ref = stackRefResolver.resolveByStackId(d.stackId).orElse(null);
    if (update == null || ref == null) {
      return fail(d, "update or stack was deleted");
    }
    URI link = consoleLink(ref, update);
    beginAttempt(d);
    PullRequestTarget target = publisher.resolve(repository, d.prNumber, d.headSha).orElse(null);
    if (target == null
        || !d.headSha.equalsIgnoreCase(target.headSha())
        || (d.prNumber != null && d.prNumber != target.number())) {
      return fail(d, "no unique open PR matching the run commit");
    }
    ReportLimits limits =
        ReportLimits.defaults()
            .withMaxBodyBytes(
                ReportLimits.defaults().maxBodyBytes() - publisher.bodyOverheadBytes());
    UpdateEventReader.EventStream stream = eventReader.stream(d.stackId, d.updateId, 50_000);
    String body =
        renderer
            .render(
                deriver
                    .derive(ref, update, stream.events(), limits)
                    .withTruncated(stream.truncated()),
                link,
                limits)
            .body();
    d.targetRepositoryId = target.repositoryId();
    d.targetPrNumber = target.number();
    d.body = body;
    // Resolution succeeded; publication gets its own retry budget.
    d.attempts = 0;
    return true;
  }

  /** Posts the frozen body; the stack lock serializes collapsing of older reports. */
  private boolean publish(ReportDelivery d, ReportPublisher publisher, Repository repository) {
    // Hash collisions only serialize extra stacks.
    if (d.body == null
        || !Boolean.TRUE.equals(
            em.createNativeQuery("SELECT pg_try_advisory_xact_lock(?1, hashtext(?2))")
                .setParameter(1, AdvisoryLock.REPORT_STACK_NAMESPACE)
                .setParameter(2, d.stackId.toString())
                .getSingleResult())) {
      return false;
    }
    beginAttempt(d);
    d.externalId =
        publisher.publishComment(
            repository,
            new PullRequestTarget(d.targetRepositoryId, d.targetPrNumber, d.headSha),
            d.stackId,
            d.updateId,
            d.generation,
            d.body);
    d.state = "delivered";
    d.settledAt = Instant.now();
    d.lastError = null;
    metrics.counter("statedb.reports.delivered").increment();
    metrics
        .timer("statedb.reports.delivery.latency")
        .record(Duration.between(d.createdAt, d.settledAt));
    LOG.infof("report delivered comment=%s attempts=%d", d.externalId, d.attempts);
    return true;
  }

  private void beginAttempt(ReportDelivery d) {
    d.attempts++;
    metrics.counter("statedb.reports.attempts").increment();
  }

  private boolean fail(ReportDelivery d, String reason) {
    d.state = "failed";
    d.settledAt = Instant.now();
    d.lastError = reason;
    metrics.counter("statedb.reports.failures").increment();
    LOG.warnf("report terminal_failure attempts=%d reason=%s", d.attempts, reason);
    return false;
  }

  private void prune() {
    // Bounded deletion uses the partial settled_at index, never the update-history table.
    em.createNativeQuery(
            """
            DELETE FROM report_deliveries WHERE id IN (
              SELECT id FROM report_deliveries WHERE state <> 'pending' AND settled_at < ?1
              ORDER BY settled_at, id LIMIT ?2 FOR UPDATE SKIP LOCKED)
            """)
        .setParameter(1, Instant.now().minus(retention))
        .setParameter(2, perTickCap)
        .executeUpdate();
  }

  private URI consoleLink(StackRef ref, StackUpdate update) {
    if (!publicUrl.enabled()) {
      return null; // The renderer omits the console link.
    }
    URI base = publicUrl.base();
    try {
      String path =
          String.join(
              "/",
              "console",
              ref.orgName(),
              ref.projectName(),
              ref.stackName(),
              update.dryRun ? "previews" : "updates",
              update.dryRun ? update.id.toString() : Integer.toString(update.version));
      return base.resolve(new URI(null, null, path, null, null));
    } catch (URISyntaxException invalid) {
      throw new ReportPublisher.Failure("invalid console report URL", false, 0);
    }
  }

  private static String text(Object value) {
    if (!(value instanceof String s)) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() || trimmed.length() > 512 ? null : trimmed;
  }

  private static String sha(Object value) {
    String s = text(value);
    return s != null && s.matches("[0-9a-fA-F]{40,64}")
        ? s.toLowerCase(java.util.Locale.ROOT)
        : null;
  }
}
