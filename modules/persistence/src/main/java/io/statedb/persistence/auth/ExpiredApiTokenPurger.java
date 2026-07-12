package io.statedb.persistence.auth;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Deletes api_tokens rows long past their expiry, so short-lived CLI tokens don't accumulate
 * forever. An expired token already fails authentication ({@code ApiTokenFinder} filters on {@code
 * expires_at}); this is housekeeping, not enforcement.
 *
 * <p>The 30-day retention keeps recent rows for operator forensics ("when was this token last
 * used") and preserves {@code stack_updates.started_by_api_token_id} attribution for a while — that
 * FK is {@code ON DELETE SET NULL}, so the purge nulls attribution on updates older than the
 * window. Multi-pod safe: concurrent DELETEs of the same rows are idempotent.
 */
@ApplicationScoped
public class ExpiredApiTokenPurger {

  private static final Logger LOG = Logger.getLogger(ExpiredApiTokenPurger.class);

  static final Duration RETENTION = Duration.ofDays(30);

  @Scheduled(every = "24h", concurrentExecution = ConcurrentExecution.SKIP)
  @Transactional
  void purge() {
    long deleted = ApiToken.delete("expiresAt < ?1", Instant.now().minus(RETENTION));
    if (deleted > 0) {
      LOG.infof("purged %d api tokens expired more than %s ago", deleted, RETENTION);
    }
  }
}
