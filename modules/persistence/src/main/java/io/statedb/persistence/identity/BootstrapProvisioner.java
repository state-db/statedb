package io.statedb.persistence.identity;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Idempotent boot-time provisioning of the first organization, admin user, membership, and
 * service-account API token from env. Operators set five {@code STATE_DB_BOOTSTRAP_*} env vars; the
 * raw token never reaches this process — only its sha256 hash does.
 *
 * <p>The bootstrap user lives under the reserved identity namespace {@code iss='local:bootstrap'},
 * {@code sub='admin'}. No real OIDC issuer can produce that pair, so there is no collision risk
 * with later real users. The provisioned token is {@code kind='service_account'} with a one-year
 * expiry; once OIDC is set up (Slice 23+) operators may revoke it or keep it as the CLI-side
 * recovery path for when the IdP is down.
 *
 * <p>Re-running with the same env is a no-op. Re-running with a changed display-name or admin email
 * updates the existing rows in place. Re-running with a new token hash <em>adds</em> a row (old
 * token stays valid until manually revoked) — token rotation is operator-driven.
 */
@ApplicationScoped
public class BootstrapProvisioner {

  private static final Logger LOG = Logger.getLogger(BootstrapProvisioner.class);

  /** Reserved {@code (iss, sub)} for the synthetic bootstrap admin. */
  public static final String BOOTSTRAP_ISS = "local:bootstrap";

  public static final String BOOTSTRAP_SUB = "admin";

  private static final String TOKEN_DESCRIPTION = "bootstrap";
  private static final long TOKEN_TTL_DAYS = 365;

  void onStart(@Observes StartupEvent ev) {
    // StartupEvent fires after Flyway has run (quarkus-flyway with migrate-at-start=true).
    // QuarkusTransaction is used in lieu of @Transactional so that the same-bean call from
    // onStart -> provision() actually gets a transaction; @Transactional only fires on
    // cross-bean invocations through the CDI proxy.
    BootstrapConfig.fromEnv()
        .ifPresentOrElse(
            config -> QuarkusTransaction.requiringNew().run(() -> provision(config)),
            () -> LOG.infof("Bootstrap skipped: %s is unset", BootstrapConfig.ENV_ORG_NAME));
  }

  /**
   * Provisions the bootstrap rows. Must be called inside a transaction (tests use
   * {@code @TestTransaction}; the {@link #onStart} path wraps with {@code QuarkusTransaction}).
   */
  public void provision(BootstrapConfig config) {
    Organization org = upsertOrg(config);
    User user = upsertBootstrapUser(config);
    ensureMembership(org.id, user.id);
    ensureToken(config.adminTokenHash(), org.id, user.id);
    LOG.infof(
        "Bootstrap admin \"%s\" provisioned in org \"%s\"", config.adminEmail(), config.orgName());
  }

  private Organization upsertOrg(BootstrapConfig config) {
    Organization org = Organization.find("name", config.orgName()).firstResult();
    if (org == null) {
      org = new Organization();
      org.name = config.orgName();
      org.displayName = config.orgDisplayName();
      org.persist();
      return org;
    }
    if (!config.orgDisplayName().equals(org.displayName)) {
      org.displayName = config.orgDisplayName();
    }
    return org;
  }

  private User upsertBootstrapUser(BootstrapConfig config) {
    User user = User.find("iss = ?1 and sub = ?2", BOOTSTRAP_ISS, BOOTSTRAP_SUB).firstResult();
    if (user == null) {
      user = new User();
      user.iss = BOOTSTRAP_ISS;
      user.sub = BOOTSTRAP_SUB;
      user.email = config.adminEmail();
      user.displayName = config.adminDisplayName();
      user.persist();
      return user;
    }
    if (!config.adminEmail().equals(user.email)) {
      user.email = config.adminEmail();
    }
    if (!config.adminDisplayName().equals(user.displayName)) {
      user.displayName = config.adminDisplayName();
    }
    return user;
  }

  private void ensureMembership(UUID orgId, UUID userId) {
    if (OrgMember.findById(new OrgMember.PK(orgId, userId)) != null) return;
    OrgMember m = new OrgMember();
    m.orgId = orgId;
    m.userId = userId;
    m.role = OrgMember.Role.owner;
    m.persist();
  }

  private void ensureToken(String tokenHash, UUID orgId, UUID userId) {
    if (ApiToken.find("tokenHash", tokenHash).firstResult() != null) return;
    ApiToken t = new ApiToken();
    t.tokenHash = tokenHash;
    t.description = TOKEN_DESCRIPTION;
    t.userId = userId;
    t.orgId = orgId;
    t.kind = ApiToken.Kind.service_account;
    t.expiresAt = Instant.now().plus(TOKEN_TTL_DAYS, ChronoUnit.DAYS);
    t.persist();
  }
}
