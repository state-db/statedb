package app.statedb.persistence.eula;

import app.statedb.persistence.AdvisoryLock;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Non-interactive acceptance channel for operators who provision state-db from infrastructure as
 * code and never open the console. Setting {@code statedb.eula.accepted-version} (env {@code
 * STATEDB_EULA_ACCEPTED_VERSION}) to the version this image ships is the operator's affirmative
 * acceptance of that agreement, and is recorded as such.
 *
 * <p>The value must name the exact version. A deployment that pins an older version does not
 * silently accept a newer agreement shipped by a later image — the gate stays closed and a site
 * administrator must accept in the console, or the operator must update the configured version.
 *
 * <p>Nothing is accepted by default: an out-of-the-box deployment still requires console
 * acceptance. Cluster-wide serialization mirrors {@code OidcProviderBootstrap}.
 */
@ApplicationScoped
public class EulaConfigurationAcceptor {

  private static final Logger LOG = Logger.getLogger(EulaConfigurationAcceptor.class);

  @Inject EulaDocument document;
  @Inject EulaAcknowledgment acknowledgment;
  @Inject AdvisoryLock advisoryLock;

  @ConfigProperty(name = "statedb.eula.accepted-version")
  Optional<String> configuredVersion;

  void onStart(@Observes StartupEvent event) {
    String configured =
        configuredVersion.map(String::trim).filter(value -> !value.isBlank()).orElse(null);
    if (configured == null) {
      return;
    }
    if (!document.version().equals(configured)) {
      LOG.warnf(
          "statedb.eula.accepted-version is %s but this image ships End User License Agreement"
              + " version %s; stack creation stays blocked until the shipped version is accepted",
          configured, document.version());
      return;
    }
    advisoryLock.runExclusive(
        AdvisoryLock.EULA_ACCEPTANCE_KEY, acknowledgment::acceptFromConfiguration);
    LOG.infof(
        "End User License Agreement version %s accepted via configuration", document.version());
  }
}
