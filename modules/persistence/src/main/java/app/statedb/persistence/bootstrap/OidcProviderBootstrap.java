package app.statedb.persistence.bootstrap;

import app.statedb.persistence.AdvisoryLock;
import app.statedb.persistence.PersistenceIds;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

/**
 * Idempotent boot-time upsert of one OIDC provider row from {@code STATEDB_OIDC_BOOTSTRAP_*} env,
 * so a fresh deployment can authenticate console users against an IdP without a manual DB insert.
 *
 * <p>Env wins on conflict: re-running with a rotated {@code CLIENT_SECRET} (or a changed issuer /
 * client id) updates the existing row in place and re-enables it. {@code scopes} are left to the
 * column default on insert and untouched on update, so an operator who tuned them via SQL keeps
 * their values across redeploys. Additional providers are added at runtime through the admin API (a
 * later slice); this only manages the single bootstrap row keyed by {@code name}.
 *
 * <p>Concurrency mirrors {@link BootstrapProvisioner}: serialized cluster-wide by {@link
 * AdvisoryLock#OIDC_PROVIDER_BOOTSTRAP_KEY}, with a single {@code INSERT ... ON CONFLICT} so the
 * per-row guarantee holds even for writers that bypass the lock. Native SQL bypasses Hibernate's
 * {@code @UpdateTimestamp}, so {@code updated_at} is set explicitly on the UPDATE branch.
 */
@ApplicationScoped
public class OidcProviderBootstrap {

  private static final Logger LOG = Logger.getLogger(OidcProviderBootstrap.class);

  @Inject EntityManager em;

  @Inject AdvisoryLock advisoryLock;

  void onStart(@Observes StartupEvent ev) {
    OidcBootstrapConfig.fromEnv()
        .ifPresentOrElse(
            config ->
                advisoryLock.runExclusive(
                    AdvisoryLock.OIDC_PROVIDER_BOOTSTRAP_KEY, () -> upsert(config)),
            () -> LOG.infof("OIDC bootstrap skipped: %s is unset", OidcBootstrapConfig.ENV_NAME));
  }

  /** Upserts the provider row. Must run inside a transaction. */
  public void upsert(OidcBootstrapConfig config) {
    em.createNativeQuery(
            """
            INSERT INTO oidc_providers (id, name, issuer_url, client_id, client_secret)
            VALUES (:id, :name, :issuer, :clientId, :secret)
            ON CONFLICT (name) DO UPDATE
              SET issuer_url = EXCLUDED.issuer_url,
                  client_id = EXCLUDED.client_id,
                  client_secret = EXCLUDED.client_secret,
                  enabled = true,
                  updated_at = now()
            """)
        .setParameter("id", PersistenceIds.newId())
        .setParameter("name", config.name())
        .setParameter("issuer", config.issuer())
        .setParameter("clientId", config.clientId())
        .setParameter("secret", config.clientSecret())
        .executeUpdate();
    LOG.infof("OIDC provider \"%s\" provisioned (issuer %s)", config.name(), config.issuer());
  }
}
