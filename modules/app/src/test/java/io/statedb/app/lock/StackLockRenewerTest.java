package io.statedb.app.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.lock.Lease;
import io.statedb.persistence.lock.StackLockAcquirer;
import io.statedb.persistence.lock.StackLockRenewer;
import io.statedb.persistence.lock.StackLockedException;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import io.statedb.persistence.update.StackUpdateStarter;
import io.statedb.persistence.update.StartUpdateRequest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackLockRenewerTest {

  private static final String ORG_NAME = "renewer-org";

  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter starter;
  @Inject StackLockAcquirer acquirer;
  @Inject StackLockRenewer renewer;
  @Inject EntityManager em;

  private UUID stackId;
  private UUID updateId;
  private UUID lockToken;

  @BeforeEach
  void seed() {
    stackId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Organization org = new Organization();
                  org.name = ORG_NAME;
                  org.displayName = "Renewer Test";
                  org.persistAndFlush();
                  StackRef ref = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
                  return ref.stackId();
                });
    updateId =
        starter
            .start(
                stackId,
                StackUpdate.Kind.PREVIEW,
                "renew-key",
                new StartUpdateRequest(
                    Map.of(), Map.of(), Map.of(), "d", "yaml", "v3.234.0", "m"))
            .updateId();
    Lease lease = acquirer.acquire(stackId, updateId, "user:a", null, null).orElseThrow();
    lockToken = lease.lockToken();
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                    .setParameter(1, ORG_NAME)
                    .executeUpdate());
  }

  @Test
  void renewAdvancesExpiresAtAndHeartbeat() {
    // Back-date both timestamps so we can detect forward movement deterministically without
    // depending on millisecond-level clock resolution.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks"
                            + "    SET expires_at = now() + interval '5 minutes',"
                            + "        heartbeat_at = now() - interval '5 minutes'"
                            + "  WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .executeUpdate());

    Instant beforeExpires = readExpiresAt();
    Instant beforeHeartbeat = readHeartbeatAt();

    Lease renewed = renewer.renew(stackId, updateId, lockToken);

    Instant afterExpires = readExpiresAt();
    Instant afterHeartbeat = readHeartbeatAt();
    assertTrue(afterExpires.isAfter(beforeExpires), "expires_at should advance");
    assertTrue(afterHeartbeat.isAfter(beforeHeartbeat), "heartbeat_at should advance");
    assertEquals(lockToken, renewed.lockToken());
  }

  @Test
  void renewWithWrongTokenIsRejected() {
    UUID wrong = UUID.randomUUID();
    Instant before = readExpiresAt();
    assertThrows(StackLockedException.class, () -> renewer.renew(stackId, updateId, wrong));
    Instant after = readExpiresAt();
    assertEquals(before, after);
  }

  @Test
  void renewOnExpiredLockIsRejected() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery(
                        "UPDATE stack_locks SET expires_at = now() - interval '1 second'"
                            + " WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .executeUpdate());

    assertThrows(StackLockedException.class, () -> renewer.renew(stackId, updateId, lockToken));
  }

  @Test
  void renewOnMissingLockIsRejected() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                em.createNativeQuery("DELETE FROM stack_locks WHERE stack_id = ?1")
                    .setParameter(1, stackId)
                    .executeUpdate());

    assertThrows(StackLockedException.class, () -> renewer.renew(stackId, updateId, lockToken));
  }

  private Instant readExpiresAt() {
    Object raw =
        em.createNativeQuery("SELECT expires_at FROM stack_locks WHERE stack_id = ?1")
            .setParameter(1, stackId)
            .getSingleResult();
    return toInstant(raw);
  }

  private Instant readHeartbeatAt() {
    Object raw =
        em.createNativeQuery("SELECT heartbeat_at FROM stack_locks WHERE stack_id = ?1")
            .setParameter(1, stackId)
            .getSingleResult();
    return toInstant(raw);
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    throw new IllegalStateException("unexpected timestamp type: " + value.getClass());
  }
}
