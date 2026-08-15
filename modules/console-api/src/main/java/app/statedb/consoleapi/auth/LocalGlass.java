package app.statedb.consoleapi.auth;

import app.statedb.persistence.bootstrap.BootstrapProvisioner;
import app.statedb.persistence.identity.OidcProvider;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Gate and credential check for the local break-glass console login.
 *
 * <p>Break-glass is Quarkus form authentication posting to {@code /api/auth/local} (see {@code
 * quarkus.http.auth.form} in {@code application.yml}); this bean owns the policy shared by the two
 * identity providers behind it:
 *
 * <ul>
 *   <li><b>Opt-in.</b> {@code STATEDB_LOCAL_GLASS_PASSWORD_HASH} (bcrypt, Modular Crypt Format)
 *       must be set. Env, never DB — recovery credentials must be readable while the IdP (and the
 *       console's own data) are unavailable.
 *   <li><b>Self-destruct.</b> No enabled {@code oidc_providers} row may exist. Checked at login
 *       <em>and</em> on every cookie restore, so existing break-glass sessions die the moment OIDC
 *       comes up. Tying the gate to system state rather than a profile flag means operators cannot
 *       lock themselves out by forgetting a flag, and re-opening it during IdP-down recovery is a
 *       deliberate act (disable the provider row).
 *   <li><b>Loopback.</b> The raw TCP source must be loopback — {@code X-Forwarded-For} is never
 *       honored — unless {@code STATEDB_LOCAL_GLASS_ALLOW_REMOTE=true} explicitly opts out (the
 *       docker-compose evaluation path, where published ports arrive from the bridge gateway).
 *       Login-time only; a minted cookie is already bearer state.
 * </ul>
 *
 * <p>A successful login authenticates as the reserved bootstrap admin identity ({@link
 * BootstrapProvisioner#BOOTSTRAP_ISS}/{@link BootstrapProvisioner#BOOTSTRAP_SUB}) — break-glass
 * cannot escalate beyond what {@code AdminAllowList} already grants that identity.
 */
@ApplicationScoped
public class LocalGlass {

  @Inject BootstrapProvisioner provisioner;

  @Inject
  @ConfigProperty(name = "statedb.local-glass.password-hash")
  Optional<String> passwordHash;

  @Inject
  @ConfigProperty(name = "statedb.local-glass.allow-remote", defaultValue = "false")
  boolean allowRemote;

  public boolean passwordConfigured() {
    return passwordHash.filter(hash -> !hash.isBlank()).isPresent();
  }

  public boolean allowRemote() {
    return allowRemote;
  }

  /**
   * Whether break-glass authentication is currently honored: password configured and no enabled
   * OIDC provider. Runs the provider count in its own transaction — call on a worker thread.
   */
  public boolean active() {
    return passwordConfigured()
        && QuarkusTransaction.requiringNew().call(() -> OidcProvider.count("enabled = true")) == 0;
  }

  public boolean originAllowed(SocketAddress remote) {
    return allowRemote || isLoopback(remote);
  }

  public boolean verify(char[] password) {
    return passwordConfigured() && BcryptUtil.matches(new String(password), passwordHash.get());
  }

  /**
   * Idempotently creates the bootstrap admin {@code users} row the break-glass session resolves to.
   * {@code SessionPrincipalFilter} and {@code CurrentUserResource} reject sessions without a user
   * row, and the row only otherwise exists when {@code STATEDB_BOOTSTRAP_*} provisioning ran.
   * Delegates to {@link BootstrapProvisioner#upsertBootstrapUser()} so concurrent logins (and
   * boot-time provisioning) converge on one atomic {@code ON CONFLICT} insert.
   */
  public void ensureAdminUser() {
    QuarkusTransaction.requiringNew().run(provisioner::upsertBootstrapUser);
  }

  public static boolean isLoopback(SocketAddress remote) {
    if (remote == null || remote.hostAddress() == null) {
      return false;
    }
    try {
      // hostAddress() is an IP literal, so this never does a DNS lookup.
      return InetAddress.getByName(remote.hostAddress()).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
