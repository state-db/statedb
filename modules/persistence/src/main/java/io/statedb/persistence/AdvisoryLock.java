package io.statedb.persistence;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Cluster-wide mutual exclusion for run-once startup work, backed by PostgreSQL transaction-scoped
 * advisory locks ({@code pg_advisory_xact_lock}). The lock lives in the shared DB and is released
 * automatically on COMMIT/ROLLBACK, so a pod crashing mid-task leaks nothing — the next pod
 * acquires the lock as soon as the dead connection is reaped.
 *
 * <p>Use this to serialize any startup task whose effect we want exactly once per cluster (e.g.
 * {@link io.statedb.persistence.bootstrap.BootstrapProvisioner}). The task body still has to be
 * idempotent — every pod will eventually take the lock and re-run, finding the work already done.
 * Idempotency + serialization together give "at most one pod does real work, every pod verifies
 * completion before serving."
 *
 * <p>Flyway's own migrate-at-start path uses the same Postgres primitive on its {@code
 * flyway_schema_history} table, so no separate lock is needed for migrations.
 *
 * <p>Lock keys are declared as constants on this class. Add a new constant rather than passing a
 * literal — it keeps the registry of keys in one place and makes collisions easy to spot.
 */
@ApplicationScoped
public class AdvisoryLock {

  /** Guards {@link io.statedb.persistence.bootstrap.BootstrapProvisioner#provision}. */
  public static final long BOOTSTRAP_KEY = 0x5354415444425F31L;

  /**
   * Guards the one-shot seeding of the singleton {@code service_secrets_keyset} row. See {@code
   * design/secrets.md} for why generation happens here and not in a Flyway migration.
   */
  public static final long SECRETS_KEYSET_BOOTSTRAP_KEY = 0x5354415444425F32L;

  @Inject EntityManager em;

  /**
   * Runs {@code task} inside a new JTA transaction that holds an exclusive Postgres advisory lock
   * on {@code key}. Concurrent callers (in this JVM or other pods) block until the holder's
   * transaction commits or rolls back.
   */
  public void runExclusive(long key, Runnable task) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                  .setParameter(1, key)
                  .getSingleResult();
              task.run();
            });
  }
}
