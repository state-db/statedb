package app.statedb.app.lock;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.LeaseExpiredException;
import app.statedb.persistence.lock.LockConfig;
import app.statedb.persistence.lock.StackLockRenewer;
import app.statedb.persistence.update.StackUpdate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackLockRenewerTest extends DatabaseTest {

  private static final String ORG_NAME = "renewer-org";

  @Inject StackLockRenewer renewer;
  @Inject LockConfig lockConfig;

  private UUID leaseId;
  private UUID originalPlaintext;

  @BeforeEach
  void seed() {
    TestDb.ActiveUpdate active =
        db.seedActiveUpdate(db.seedStack(ORG_NAME).stackId(), StackUpdate.Kind.PREVIEW);
    leaseId = active.leaseId();
    originalPlaintext = active.leaseToken();
  }

  @Test
  void renewAdvancesExpiresAtAndHeartbeatAndRotatesToken() {
    // Back-date both timestamps so we can detect forward movement deterministically without
    // depending on millisecond-level clock resolution.
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks"
                            + "    SET expires_at = now() + interval '5 minutes',"
                            + "        heartbeat_at = now() - interval '5 minutes'"
                            + "  WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());

    byte[] hashBefore = readHash();
    Instant beforeExpires = readExpiresAt();
    Instant beforeHeartbeat = readHeartbeatAt();

    Lease renewed = renewer.renew(leaseId, null);

    Instant afterExpires = readExpiresAt();
    Instant afterHeartbeat = readHeartbeatAt();
    byte[] hashAfter = readHash();
    assertTrue(afterExpires.isAfter(beforeExpires), "expires_at should advance");
    assertTrue(afterHeartbeat.isAfter(beforeHeartbeat), "heartbeat_at should advance");
    assertEquals(leaseId, renewed.leaseId());
    assertNotEquals(originalPlaintext, renewed.plaintextToken(), "token must rotate");
    // The stored hash must change too — proves the rotation reached the DB, not just the
    // response object.
    assertTrue(!java.util.Arrays.equals(hashBefore, hashAfter), "lock_token_hash should rotate");
  }

  @Test
  void renewPastMaxLifetimeIsRejected() {
    // Push the ceiling into the past while leaving expires_at in the future. Renew should
    // refuse — the bearer cannot be kept alive past max_expires_at even if currently valid.
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks"
                            + "    SET expires_at = now() + interval '5 minutes',"
                            + "        max_expires_at = now() - interval '1 second'"
                            + "  WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());

    assertThrows(LeaseExpiredException.class, () -> renewer.renew(leaseId, null));
  }

  @Test
  void renewClampsExpiresAtToMaxLifetime() {
    // Ceiling is only one second away; the next renew would normally push expires_at out by
    // the TTL but must clamp to max_expires_at instead.
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks"
                            + "    SET expires_at = now() + interval '5 minutes',"
                            + "        max_expires_at = now() + interval '1 second'"
                            + "  WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());

    Instant maxBefore = readMaxExpiresAt();
    Lease renewed = renewer.renew(leaseId, null);
    Instant clamped = readExpiresAt();
    assertTrue(
        !clamped.isAfter(maxBefore), "expires_at must not exceed max_expires_at after renew");
    assertTrue(renewed.expiresAt().equals(clamped), "response pins to clamped value");
  }

  @Test
  void renewOnExpiredLockIsRejected() {
    requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks SET expires_at = now() - interval '1 second'"
                            + " WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());

    assertThrows(LeaseExpiredException.class, () -> renewer.renew(leaseId, null));
  }

  @Test
  void requestedDurationIsHonoredWithinBounds() {
    assertEquals(Duration.ofSeconds(300), renewer.renew(leaseId, 300L).duration());
  }

  @Test
  void requestedDurationBelowFloorIsClampedUp() {
    assertEquals(Duration.ofSeconds(30), renewer.renew(leaseId, 1L).duration());
  }

  @Test
  void requestedDurationAboveLeaseTtlIsClampedDown() {
    Duration day = Duration.ofDays(1);
    assertEquals(lockConfig.leaseTtl(), renewer.renew(leaseId, day.toSeconds()).duration());
  }

  @Test
  void invalidRequestedDurationFallsBackToLeaseTtl() {
    assertEquals(lockConfig.leaseTtl(), renewer.renew(leaseId, 0L).duration());
    assertEquals(lockConfig.leaseTtl(), renewer.renew(leaseId, -5L).duration());
    assertEquals(lockConfig.leaseTtl(), renewer.renew(leaseId, null).duration());
  }

  @Test
  void renewOnMissingLockIsRejected() {
    requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM stack_locks WHERE id = ?1")
                    .setParameter(1, leaseId)
                    .executeUpdate());

    assertThrows(LeaseExpiredException.class, () -> renewer.renew(leaseId, null));
  }

  private Instant readExpiresAt() {
    Object raw =
        em.createNativeQuery("SELECT expires_at FROM stack_locks WHERE id = ?1")
            .setParameter(1, leaseId)
            .getSingleResult();
    return toInstant(raw);
  }

  private Instant readMaxExpiresAt() {
    Object raw =
        em.createNativeQuery("SELECT max_expires_at FROM stack_locks WHERE id = ?1")
            .setParameter(1, leaseId)
            .getSingleResult();
    return toInstant(raw);
  }

  private Instant readHeartbeatAt() {
    Object raw =
        em.createNativeQuery("SELECT heartbeat_at FROM stack_locks WHERE id = ?1")
            .setParameter(1, leaseId)
            .getSingleResult();
    return toInstant(raw);
  }

  private byte[] readHash() {
    return (byte[])
        em.createNativeQuery("SELECT lock_token_hash FROM stack_locks WHERE id = ?1")
            .setParameter(1, leaseId)
            .getSingleResult();
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
