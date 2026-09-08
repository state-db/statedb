package app.statedb.persistence.eula;

/**
 * Thrown when a deployment tries to create a stack before a site administrator has accepted the
 * EULA version this image ships.
 */
public class EulaNotAcceptedException extends RuntimeException {

  private final String requiredVersion;

  public EulaNotAcceptedException(String action, String requiredVersion) {
    super(
        "%s is unavailable: a site administrator must accept the state-db End User License Agreement (version %s) in the web console before this deployment can create stacks."
            .formatted(action, requiredVersion));
    this.requiredVersion = requiredVersion;
  }

  public String requiredVersion() {
    return requiredVersion;
  }
}
