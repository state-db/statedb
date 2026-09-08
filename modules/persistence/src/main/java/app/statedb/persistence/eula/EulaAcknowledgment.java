package app.statedb.persistence.eula;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Deep EULA module: callers ask whether this deployment may create stacks, not how acceptance is
 * stored. The gate is deployment-wide, not per-org — the agreement binds the licensee operating the
 * deployment.
 *
 * <p>The check is a single indexed count against {@code eula_acceptances}, deliberately uncached:
 * stack creation is rare next to the rest of a deployment's traffic, and an uncached read is what
 * lets one pod's acceptance take effect on every other pod immediately.
 */
@ApplicationScoped
public class EulaAcknowledgment {

  @Inject EulaDocument document;

  /** Whether the version this image ships has been accepted. */
  public boolean accepted() {
    return EulaAcceptance.count("version", document.version()) > 0;
  }

  /**
   * @throws EulaNotAcceptedException if no site administrator has accepted the current version
   */
  public void requireAccepted(String action) {
    if (!accepted()) {
      throw new EulaNotAcceptedException(action, document.version());
    }
  }

  /** The current agreement plus this deployment's acceptance state, for the console. */
  public EulaStatus status() {
    Optional<EulaAcceptance> first =
        EulaAcceptance.<EulaAcceptance>find(
                "version", Sort.by("createdAt").ascending(), document.version())
            .firstResultOptional();
    return new EulaStatus(
        document.version(),
        document.sha256(),
        first.isPresent(),
        first.map(acceptance -> acceptance.acceptedVia).orElse(null),
        first.map(acceptance -> acceptance.acceptedByUserId).orElse(null),
        first.map(acceptance -> acceptance.createdAt).orElse(null));
  }

  /**
   * Records {@code userId}'s acceptance of the current version. Re-accepting a version this user
   * already accepted is a no-op, so a resubmitted console form does not fail. Two concurrent
   * accepts by the same user still race to the {@code (version, accepted_by_user_id)} unique index;
   * the loser's transaction rolls back and the console retry finds the acceptance already recorded.
   *
   * @return the acceptance state after the call
   */
  @Transactional
  public EulaStatus accept(UUID userId, String acceptedFrom) {
    boolean alreadyAccepted =
        EulaAcceptance.count("version = ?1 and acceptedByUserId = ?2", document.version(), userId)
            > 0;
    if (!alreadyAccepted) {
      EulaAcceptance acceptance = new EulaAcceptance();
      acceptance.version = document.version();
      acceptance.documentSha256 = document.sha256();
      acceptance.acceptedVia = EulaAcceptance.Channel.console;
      acceptance.acceptedByUserId = userId;
      acceptance.acceptedFrom = acceptedFrom;
      acceptance.persist();
    }
    return status();
  }

  /**
   * Records the operator's configured acceptance. {@link EulaConfigurationAcceptor} serializes this
   * cluster-wide at startup; the partial unique index on {@code (version) WHERE accepted_via =
   * 'configuration'} keeps a restart from appending a second row. Joins the caller's transaction
   * when there is one.
   */
  @Transactional
  public void acceptFromConfiguration() {
    if (EulaAcceptance.count(
            "version = ?1 and acceptedVia = ?2",
            document.version(),
            EulaAcceptance.Channel.configuration)
        > 0) {
      return;
    }
    EulaAcceptance acceptance = new EulaAcceptance();
    acceptance.version = document.version();
    acceptance.documentSha256 = document.sha256();
    acceptance.acceptedVia = EulaAcceptance.Channel.configuration;
    acceptance.persist();
  }

  /** Acceptance state of the EULA version this image ships. */
  public record EulaStatus(
      String version,
      String documentSha256,
      boolean accepted,
      EulaAcceptance.Channel acceptedVia,
      UUID acceptedByUserId,
      Instant acceptedAt) {}
}
