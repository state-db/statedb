package app.statedb.consoleapi.auth;

import app.statedb.persistence.bootstrap.BootstrapProvisioner;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Synthetic {@link JsonWebToken} principal for break-glass sessions, carrying only the reserved
 * bootstrap admin {@code iss}/{@code sub}. Downstream console code ({@code SessionPrincipalFilter},
 * {@code CurrentUserResource}, Cedar role resolution) reads nothing else from the session
 * principal, so break-glass identities flow through the same path as OIDC ones.
 */
final class LocalGlassPrincipal implements JsonWebToken {

  static final LocalGlassPrincipal INSTANCE = new LocalGlassPrincipal();

  /**
   * Principal name the form-auth mechanism stores in the encrypted cookie and hands back to {@code
   * LocalGlassTrustedIdentityProvider} on restore.
   */
  static final String COOKIE_PRINCIPAL = BootstrapProvisioner.BOOTSTRAP_SUB;

  private static final Map<String, Object> CLAIMS =
      Map.of(
          "iss", BootstrapProvisioner.BOOTSTRAP_ISS,
          "sub", BootstrapProvisioner.BOOTSTRAP_SUB);

  private LocalGlassPrincipal() {}

  @Override
  public String getName() {
    return COOKIE_PRINCIPAL;
  }

  @Override
  public Set<String> getClaimNames() {
    return CLAIMS.keySet();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getClaim(String claimName) {
    return (T) CLAIMS.get(claimName);
  }
}
