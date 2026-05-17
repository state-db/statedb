package io.statedb.persistence.bootstrap;

import io.quarkus.runtime.StartupEvent;
import io.statedb.persistence.AdvisoryLock;
import io.statedb.persistence.BaseEntity;
import io.statedb.persistence.TimestampedEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
 *
 * <p>Concurrency: in multi-pod deployments the work is serialized cluster-wide by an {@link
 * AdvisoryLock} on {@link AdvisoryLock#BOOTSTRAP_KEY} — one pod runs {@link #provision}, the rest
 * block until its transaction commits and then re-run against the now-populated rows. Each upsert
 * is still a single {@code INSERT ... ON CONFLICT} statement, so the per-row guarantee holds even
 * for writers that bypass the lock (admin scripts, ad-hoc SQL). Bootstrap rows use
 * application-generated UUIDv4 — they're written once and rarely updated, so the temporal-ordering
 * benefit that {@link BaseEntity}'s UUIDv7 generator provides for hot inserts doesn't apply here.
 * Native SQL also bypasses Hibernate's {@code @UpdateTimestamp}, so {@code updated_at} is set
 * explicitly on the UPDATE branch of the conflict clause for the {@link TimestampedEntity} tables.
 */
@ApplicationScoped
public class BootstrapProvisioner {

  private static final Logger LOG = Logger.getLogger(BootstrapProvisioner.class);

  /** Reserved {@code (iss, sub)} for the synthetic bootstrap admin. */
  public static final String BOOTSTRAP_ISS = "local:bootstrap";

  public static final String BOOTSTRAP_SUB = "admin";

  private static final String TOKEN_DESCRIPTION = "bootstrap";

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
    UUID userId = upsertBootstrapUser(config);
    ensureMembership(orgId, userId);
    ensureToken(config.adminTokenHash(), orgId, userId);
    LOG.infof(
        "Bootstrap admin \"%s\" provisioned in org \"%s\"", config.adminEmail(), config.orgName());
  }

  private UUID upsertOrg(BootstrapConfig config) {
    em.createNativeQuery(
            """
            INSERT INTO organizations (id, name, display_name)
            VALUES (:id, :name, :display)
            ON CONFLICT (name) DO UPDATE
              SET display_name = EXCLUDED.display_name, updated_at = now()
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("name", config.orgName())
        .setParameter("display", config.orgDisplayName())
        .executeUpdate();
    return (UUID)
        em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
            .setParameter("name", config.orgName())
            .getSingleResult();
  }

  private UUID upsertBootstrapUser(BootstrapConfig config) {
    em.createNativeQuery(
            """
            INSERT INTO users (id, iss, sub, email, display_name)
            VALUES (:id, :iss, :sub, :email, :display)
            ON CONFLICT (iss, sub) DO UPDATE
              SET email = EXCLUDED.email,
                  display_name = EXCLUDED.display_name,
                  updated_at = now()
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("iss", BOOTSTRAP_ISS)
        .setParameter("sub", BOOTSTRAP_SUB)
        .setParameter("email", config.adminEmail())
        .setParameter("display", config.adminDisplayName())
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

  private void ensureToken(String tokenHash, UUID orgId, UUID userId) {
    em.createNativeQuery(
            """
            INSERT INTO api_tokens (id, token_hash, description, user_id, org_id, kind, expires_at)
            VALUES (:id, :hash, :desc, :user, :org, 'service_account', now() + interval '1 year')
            ON CONFLICT (token_hash) DO NOTHING
            """)
        .setParameter("id", UUID.randomUUID())
        .setParameter("hash", tokenHash)
        .setParameter("desc", TOKEN_DESCRIPTION)
        .setParameter("user", userId)
        .setParameter("org", orgId)
        .executeUpdate();
  }
}
