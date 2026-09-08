package app.statedb.app.ops;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Logs slow server-side requests with JDBC pool state.
 *
 * <p>Browser timings include client network costs, while ALB {@code TargetResponseTime} only gives
 * minute-level aggregates. This logger keeps the path, status, and server elapsed time together for
 * rare outliers.
 *
 * <p>The pool counters show whether the request opened a fresh database connection or waited for
 * one, which is otherwise hard to distinguish from query time.
 */
@ApplicationScoped
public class SlowRequestLogger {

  private static final Logger LOG = Logger.getLogger("slow-request");

  /** After static-resource and SPA-fallback handlers, so timing covers the whole request. */
  private static final int ORDER = Integer.MIN_VALUE + 100;

  @Inject AgroalDataSource dataSource;

  @ConfigProperty(name = "statedb.ops.slow-request-threshold", defaultValue = "PT0.25S")
  Duration threshold;

  void init(@Observes Router router) {
    router.route().order(ORDER).handler(this::time);
  }

  private void time(RoutingContext rc) {
    AgroalDataSourceMetrics metrics = dataSource.getMetrics();
    long startedAt = System.nanoTime();
    long connectionsCreatedBefore = metrics.creationCount();

    // bodyEndHandler does not run for a dropped or reset connection; endHandler does.
    rc.addEndHandler(
        ignored -> {
          long elapsedNanos = System.nanoTime() - startedAt;
          if (elapsedNanos < threshold.toNanos()) {
            return;
          }
          LOG.infof(
              "slow_request method=%s path=%s status=%d duration_ms=%d created=%d"
                  + " active=%d available=%d awaiting=%d blocked_max_ms=%d",
              rc.request().method().name(),
              rc.normalizedPath(),
              rc.response().getStatusCode(),
              elapsedNanos / 1_000_000L,
              metrics.creationCount() - connectionsCreatedBefore,
              metrics.activeCount(),
              metrics.availableCount(),
              metrics.awaitingCount(),
              metrics.blockingTimeMax().toMillis());
        });

    rc.next();
  }
}
