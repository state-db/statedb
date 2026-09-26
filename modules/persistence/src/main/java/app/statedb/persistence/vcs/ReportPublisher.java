package app.statedb.persistence.vcs;

import java.util.Optional;
import java.util.UUID;

/** Verified target resolution and immutable, retry-safe comment publication. */
public interface ReportPublisher {

  boolean supports(String connectionKind, String host);

  /** The most bytes {@link #publishComment} adds around a body, taken out of the body budget. */
  default int bodyOverheadBytes() {
    return 0;
  }

  /** The open PR whose head is {@code headSha}, and whose number is {@code prNumber} if given. */
  Optional<PullRequestTarget> resolve(Repository repository, Long prNumber, String headSha);

  /** Reuse this run's marker, then collapse older reports for the stack without editing bodies. */
  String publishComment(
      Repository repository,
      PullRequestTarget target,
      UUID stackId,
      UUID updateId,
      long generation,
      String body);

  record Repository(
      UUID connectionId, String host, String repositoryId, String owner, String name) {}

  /** Host-verified and immutable destination for a report write. */
  record PullRequestTarget(String repositoryId, long number, String headSha) {
    public PullRequestTarget {
      if (repositoryId == null || repositoryId.isBlank()) {
        throw new IllegalArgumentException("repositoryId is required");
      }
      if (number <= 0) {
        throw new IllegalArgumentException("pull request number must be positive");
      }
      if (headSha == null || headSha.isBlank()) {
        throw new IllegalArgumentException("headSha is required");
      }
    }
  }

  /** Definitive provider failures stop immediately; transient failures carry a retry delay. */
  class Failure extends RuntimeException {
    public final boolean retryable;
    public final long retryAfterSeconds;

    public Failure(String message, boolean retryable, long retryAfterSeconds) {
      super(message);
      this.retryable = retryable;
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }
}
