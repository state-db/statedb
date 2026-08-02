package app.statedb.persistence.secrets;

import app.statedb.persistence.AdvisoryLock;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.security.GeneralSecurityException;
import org.jboss.logging.Logger;

/**
 * One-shot seeding of the singleton {@code service_secrets_keyset} row. Runs under {@link
 * AdvisoryLock#SECRETS_KEYSET_BOOTSTRAP_KEY} so concurrent pods serialize and only the leader
 * generates fresh key material. Followers find the row inserted and no-op.
 *
 * <p>Key material is generated in-process from Tink's {@code SecureRandom} and inserted as a bound
 * parameter — it never lives in Flyway history, env vars, or shell history.
 */
@ApplicationScoped
public class ServiceSecretsKeysetSeeder {

  private static final Logger LOG = Logger.getLogger(ServiceSecretsKeysetSeeder.class);

  @Inject EntityManager em;

  @Inject AdvisoryLock advisoryLock;

  void onStart(@Observes StartupEvent ev) {
    advisoryLock.runExclusive(AdvisoryLock.SECRETS_KEYSET_BOOTSTRAP_KEY, this::seed);
  }

  /** Seeds the row if absent. Idempotent: a present row is left untouched. */
  public void seed() {
    Number existing =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM service_secrets_keyset").getSingleResult();
    if (existing.longValue() > 0) {
      return;
    }
    byte[] material = generate();
    em.createNativeQuery(
            "INSERT INTO service_secrets_keyset (id, key_material) VALUES (1, ?1) "
                + "ON CONFLICT (id) DO NOTHING")
        .setParameter(1, material)
        .executeUpdate();
    LOG.info("Seeded service_secrets_keyset");
  }

  private static byte[] generate() {
    try {
      AeadConfig.register();
      KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
      return TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to generate AES-256-GCM keyset", e);
    }
  }
}
