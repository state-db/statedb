package app.statedb.persistence.bootstrap;

import app.statedb.persistence.AdvisoryLock;
import app.statedb.persistence.BaseEntity;
import app.statedb.persistence.PersistenceIds;
import app.statedb.persistence.TimestampedEntity;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Idempotent boot-time provisioning of the first organization plus the bootstrap admin's user and
 * membership rows from env. Operators set {@code STATEDB_BOOTSTRAP_ORG_NAME} (and optionally a
 * display name); no credential material is involved — CLI tokens are minted through a console
 * session (break-glass or OIDC) via the {@code pulumi login} browser flow.
 *
 * <p>The bootstrap user lives under the reserved identity namespace {@code iss='local:bootstrap'},
 * {@code sub='admin'}. No real OIDC issuer can produce that pair, so there is no collision risk
 * with later real users. It is the same identity the break-glass console login authenticates as
 * ({@code LocalGlass}), and the membership seeded here is what gives that identity a default org on
 * the Pulumi surface.
 *
 * <p>Re-running with the same env is a no-op. Re-running with a changed org display-name updates
 * the existing row in place.
 *
 * <p>Concurrency: in multi-pod deployments the work is serialized cluster-wide by an {@link
 * AdvisoryLock} on {@link AdvisoryLock#BOOTSTRAP_KEY} — one pod runs {@link #provision}, the rest
 * block until its transaction commits and then re-run against the now-populated rows. Each upsert
 * is still a single {@code INSERT ... ON CONFLICT} statement, so the per-row guarantee holds even
 * for writers that bypass the lock (admin scripts, ad-hoc SQL). {@link PersistenceIds} supplies the
 * same UUIDv7 strategy as {@link BaseEntity}. Native SQL also bypasses Hibernate's
 * {@code @UpdateTimestamp}, so {@code updated_at} is set explicitly on the UPDATE branch of the
 * conflict clause for the {@link TimestampedEntity} tables.
 */
@ApplicationScoped
public class BootstrapProvisioner {

  private static final Logger LOG = Logger.getLogger(BootstrapProvisioner.class);

  /** Reserved {@code (iss, sub)} for the synthetic bootstrap admin. */
  public static final String BOOTSTRAP_ISS = "local:bootstrap";

  public static final String BOOTSTRAP_SUB = "admin";

  @Inject EntityManager em;

  @Inject AdvisoryLock advisoryLock;

  void onStart(@Observes StartupEvent ev) {
    // StartupEvent fires after Flyway has run (quarkus-flyway with migrate-at-start=true).
    // AdvisoryLock#runExclusive opens its own transaction and holds a Postgres advisory lock for
    // its duration, so concurrent pods serialize through provision() rather than each racing the
    // same upserts. The per-row ON CONFLICT clauses still guarantee correctness for any writer
    // that bypasses the lock.
    BootstrapConfig.fromEnv()
        .ifPresentOrElse(
            config ->
                advisoryLock.runExclusive(AdvisoryLock.BOOTSTRAP_KEY, () -> provision(config)),
            () -> LOG.infof("Bootstrap skipped: %s is unset", BootstrapConfig.ENV_ORG_NAME));
  }

  /**
   * Provisions the bootstrap rows. Must be called inside a transaction (tests use
   * {@code @TestTransaction}; the {@link #onStart} path wraps with {@code QuarkusTransaction}).
   */
  public void provision(BootstrapConfig config) {
    UUID orgId = upsertOrg(config);
    UUID userId = upsertBootstrapUser();
    ensureMembership(orgId, userId);
    LOG.infof(
        "Bootstrap org \"%s\" provisioned with the bootstrap admin as owner", config.orgName());
  }

  private UUID upsertOrg(BootstrapConfig config) {
    em.createNativeQuery(
            """
            INSERT INTO organizations (id, name, display_name)
            VALUES (:id, :name, :display)
            ON CONFLICT (name) DO UPDATE
              SET display_name = EXCLUDED.display_name, updated_at = now()
            """)
        .setParameter("id", PersistenceIds.newId())
        .setParameter("name", config.orgName())
        .setParameter("display", config.orgDisplayName())
        .executeUpdate();
    return (UUID)
        em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
            .setParameter("name", config.orgName())
            .getSingleResult();
  }

  /**
   * Insert-if-absent for the bootstrap admin {@code users} row. Also called on every break-glass
   * login ({@code LocalGlass#ensureAdminUser}); the single {@code ON CONFLICT} statement keeps
   * concurrent logins and boot-time provisioning from racing each other. Requires an active
   * transaction.
   */
  public UUID upsertBootstrapUser() {
    em.createNativeQuery(
            """
            INSERT INTO users (id, iss, sub, display_name)
            VALUES (:id, :iss, :sub, :display)
            ON CONFLICT (iss, sub) DO NOTHING
            """)
        .setParameter("id", PersistenceIds.newId())
        .setParameter("iss", BOOTSTRAP_ISS)
        .setParameter("sub", BOOTSTRAP_SUB)
        .setParameter("display", "Break-glass admin")
        .executeUpdate();
    return (UUID)
        em.createNativeQuery("SELECT id FROM users WHERE iss = :iss AND sub = :sub")
            .setParameter("iss", BOOTSTRAP_ISS)
            .setParameter("sub", BOOTSTRAP_SUB)
            .getSingleResult();
  }

  private void ensureMembership(UUID orgId, UUID userId) {
    em.createNativeQuery(
            """
            INSERT INTO org_members (org_id, user_id, role)
            VALUES (:org, :user, 'owner')
            ON CONFLICT (org_id, user_id) DO NOTHING
            """)
        .setParameter("org", orgId)
        .setParameter("user", userId)
        .executeUpdate();
  }
}
