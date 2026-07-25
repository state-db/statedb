package io.statedb.app.lock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.statedb.persistence.lock.LockConfig;
import io.statedb.persistence.lock.LockConfigValidator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LockConfigValidatorTest {

  @Test
  void acceptsCeilingEqualToOrGreaterThanTtl() {
    assertDoesNotThrow(() -> run(Duration.ofHours(1), Duration.ofHours(1)));
    assertDoesNotThrow(() -> run(Duration.ofHours(1), Duration.ofHours(24)));
  }

  @Test
  void rejectsCeilingShorterThanTtl() {
    assertThrows(
        IllegalStateException.class, () -> run(Duration.ofHours(1), Duration.ofMinutes(5)));
  }

  private static void run(Duration ttl, Duration maxLifetime) {
    LockConfigValidator v = new LockConfigValidator();
    v.lockConfig =
        new LockConfig() {
          @Override
          public Duration leaseTtl() {
            return ttl;
          }

          @Override
          public Duration heartbeatInterval() {
            return Duration.ofSeconds(30);
          }

          @Override
          public Duration maxLeaseLifetime() {
            return maxLifetime;
          }

          @Override
          public Duration compactionWait() {
            return Duration.ofSeconds(30);
          }
        };
    v.onStart(null);
  }
}
