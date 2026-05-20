package io.statedb.persistence.secrets;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import java.security.GeneralSecurityException;

/**
 * Lazily resolves the {@link Aead} primitive from the singleton {@code service_secrets_keyset} row.
 * The keyset bytes are read once on first use and the parsed handle is cached; the raw bytes never
 * leave this object.
 *
 * <p>Loading is deferred (rather than running on {@code StartupEvent}) so that the bootstrap seeder
 * — which is also a startup observer — has run before any request hits {@link #aead()}. A missing
 * row at lookup time fails fast: silently regenerating outside the advisory-lock path would let a
 * partial DB restore rekey a deployment without anyone noticing.
 */
@ApplicationScoped
public class ServiceSecretsAead {

  @Inject EntityManager em;

  private volatile Aead aead;

  /** Returns the in-memory {@link Aead}, loading it from Postgres on first call. */
  public Aead aead() {
    Aead local = aead;
    if (local == null) {
      synchronized (this) {
        local = aead;
        if (local == null) {
          local = load();
          aead = local;
        }
      }
    }
    return local;
  }

  @Transactional
  Aead load() {
    try {
      AeadConfig.register();
      byte[] material;
      try {
        material =
            (byte[])
                em.createNativeQuery("SELECT key_material FROM service_secrets_keyset WHERE id = 1")
                    .getSingleResult();
      } catch (NoResultException e) {
        throw new IllegalStateException(
            "service_secrets_keyset row is missing; bootstrap did not seed it", e);
      }
      KeysetHandle handle =
          TinkProtoKeysetFormat.parseKeyset(material, InsecureSecretKeyAccess.get());
      return handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to parse service_secrets_keyset", e);
    }
  }
}
