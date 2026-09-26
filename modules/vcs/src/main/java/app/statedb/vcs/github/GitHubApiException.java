package app.statedb.vcs.github;

import app.statedb.persistence.vcs.ReportPublisher;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Distinguishes rate limits and unavailable service from definitive HTTP failures. */
public final class GitHubApiException extends ReportPublisher.Failure {

  private final int status;

  GitHubApiException(int status, String message) {
    this(status, message, HttpHeaders.of(Map.of(), (a, b) -> true));
  }

  GitHubApiException(int status, String message, HttpHeaders headers) {
    super(
        "GitHub API returned HTTP " + status + ": " + message,
        status == 408
            || status == 429
            || status >= 500
            || (status == 403
                && (headers.firstValue("Retry-After").isPresent()
                    || headers.firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("rate limit"))),
        retryDelay(headers));
    this.status = status;
  }

  private static long retryDelay(HttpHeaders headers) {
    long delay = 60;
    String retry = headers.firstValue("Retry-After").orElse("");
    try {
      delay = Math.max(delay, Long.parseLong(retry));
    } catch (NumberFormatException ignored) {
      try {
        delay =
            Math.max(
                delay,
                ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                    - Instant.now().getEpochSecond());
      } catch (java.time.format.DateTimeParseException invalid) {
        // Fall back to the rate-limit reset or a one-minute backoff.
      }
    }
    if (headers.firstValue("X-RateLimit-Remaining").orElse("").equals("0")) {
      try {
        delay =
            Math.max(
                delay,
                Long.parseLong(headers.firstValue("X-RateLimit-Reset").orElse("0"))
                    - Instant.now().getEpochSecond());
      } catch (NumberFormatException ignored) {
        // Malformed remote header: retain the conservative backoff.
      }
    }
    return delay;
  }

  public int status() {
    return status;
  }
}
