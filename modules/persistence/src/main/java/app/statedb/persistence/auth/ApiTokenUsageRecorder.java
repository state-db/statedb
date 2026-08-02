package app.statedb.persistence.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Advances {@code api_tokens.last_used_at} when a token authenticates a request. This is the
 * operator's only signal for whether a (possibly leaked) token is in active use, so it must be
 * written from the auth path — but not on every request: the update is throttled to once per {@link
 * #THROTTLE} per token, so a busy CLI run costs one extra UPDATE instead of hundreds.
 */
@ApplicationScoped
public class ApiTokenUsageRecorder {

  static final Duration THROTTLE = Duration.ofMinutes(5);

  @Transactional
  public void touch(UUID tokenId) {
    Instant now = Instant.now();
    ApiToken.update(
        "lastUsedAt = ?1 where id = ?2 and (lastUsedAt is null or lastUsedAt < ?3)",
        now,
        tokenId,
        now.minus(THROTTLE));
  }
}
