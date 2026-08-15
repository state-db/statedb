package app.statedb.persistence.lock;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Fails fast when {@code statedb.lock.max-lease-lifetime} is shorter than {@code
 * statedb.lock.lease-ttl}. The ceiling is meant to cap renewed lifetime; a configured cap below the
 * initial TTL is almost certainly a typo and would otherwise let the first acquire issue a bearer
 * that outlives its own ceiling.
 */
@ApplicationScoped
public class LockConfigValidator {

  @Inject public LockConfig lockConfig;

  public void onStart(@Observes StartupEvent event) {
    if (lockConfig.maxLeaseLifetime().compareTo(lockConfig.leaseTtl()) < 0) {
      throw new IllegalStateException(
          "statedb.lock.max-lease-lifetime ("
              + lockConfig.maxLeaseLifetime()
              + ") must be >= statedb.lock.lease-ttl ("
              + lockConfig.leaseTtl()
              + ")");
    }
  }
}
