package io.statedb.app.ops;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.function.ToDoubleFunction;

/**
 * Publishes Agroal pool metrics to Micrometer.
 *
 * <p>Quarkus feeds them only to the legacy {@code MetricsFactory} SPI, which {@code
 * quarkus-micrometer} does not implement, so without this the scrape carries no pool data. Values
 * are non-zero only while {@code quarkus.datasource.jdbc.metrics.enabled} is true.
 */
@ApplicationScoped
public class AgroalPoolMetricsBinder {

  @Inject AgroalDataSource dataSource;

  @Inject MeterRegistry registry;

  void bind(@Observes StartupEvent event) {
    AgroalDataSourceMetrics metrics = dataSource.getMetrics();

    gauge(
        "agroal.active.count",
        metrics,
        AgroalDataSourceMetrics::activeCount,
        "Connections currently in use");
    gauge(
        "agroal.available.count",
        metrics,
        AgroalDataSourceMetrics::availableCount,
        "Idle connections ready to be acquired");
    gauge(
        "agroal.awaiting.count",
        metrics,
        AgroalDataSourceMetrics::awaitingCount,
        "Threads blocked waiting for a connection");
    gauge(
        "agroal.max.used.count",
        metrics,
        AgroalDataSourceMetrics::maxUsedCount,
        "High-water mark of connections in use");

    seconds(
        "agroal.blocking.time.average",
        metrics,
        AgroalDataSourceMetrics::blockingTimeAverage,
        "Mean time spent waiting for a connection");
    seconds(
        "agroal.blocking.time.max",
        metrics,
        AgroalDataSourceMetrics::blockingTimeMax,
        "Longest time spent waiting for a connection");

    counter(
        "agroal.acquire.count",
        metrics,
        AgroalDataSourceMetrics::acquireCount,
        "Connections acquired");
    counter(
        "agroal.creation.count",
        metrics,
        AgroalDataSourceMetrics::creationCount,
        "Connections created");
    counter(
        "agroal.destroy.count",
        metrics,
        AgroalDataSourceMetrics::destroyCount,
        "Connections destroyed");
    counter(
        "agroal.leak.detection.count",
        metrics,
        AgroalDataSourceMetrics::leakDetectionCount,
        "Connections flagged as leaked");
  }

  private void gauge(
      String name,
      AgroalDataSourceMetrics metrics,
      ToDoubleFunction<AgroalDataSourceMetrics> value,
      String description) {
    Gauge.builder(name, metrics, value).description(description).register(registry);
  }

  private void seconds(
      String name,
      AgroalDataSourceMetrics metrics,
      java.util.function.Function<AgroalDataSourceMetrics, Duration> value,
      String description) {
    Gauge.builder(name, metrics, m -> value.apply(m).toNanos() / 1_000_000_000d)
        .description(description)
        .baseUnit("seconds")
        .register(registry);
  }

  private void counter(
      String name,
      AgroalDataSourceMetrics metrics,
      ToDoubleFunction<AgroalDataSourceMetrics> value,
      String description) {
    FunctionCounter.builder(name, metrics, value).description(description).register(registry);
  }
}
