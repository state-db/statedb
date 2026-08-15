package app.statedb.persistence.lock;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

@ConfigMapping(prefix = "statedb.lock")
public interface LockConfig {

  @WithDefault("1h")
  Duration leaseTtl();

  @WithDefault("30s")
  Duration heartbeatInterval();

  // Hard ceiling on total lease lifetime, including renewals. Stops a stolen
  // bearer from being renewed forever when the legit client crashed without
  // ever rotating the token. Sized to cover normal `pulumi up` / `destroy`
  // runs; pathological multi-day operations can raise the override.
  @WithDefault("24h")
  Duration maxLeaseLifetime();

  // How long acquisition waits for a queued compaction before proceeding anyway. Zero disables.
  @WithDefault("30s")
  Duration compactionWait();
}
