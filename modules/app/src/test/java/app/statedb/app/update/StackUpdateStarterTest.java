package app.statedb.app.update;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackImporter;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRenamer;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import app.statedb.persistence.update.StartedUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackUpdateStarterTest {

  private static final String ORG_NAME = "starter-org";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject StackCreator stackCreator;
  @Inject StackImporter stackImporter;
  @Inject StackRenamer stackRenamer;
  @Inject StackUpdateStarter starter;
  @Inject EntityManager em;

  private StackRef stackRef;
  private UUID stackId;

  @BeforeEach
  void seed() {
    stackId =
        read(
            () -> {
              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "Starter Test";
              org.persistAndFlush();
              stackRef = stackCreator.create(org.id, ORG_NAME, "proj", "dev", Map.of());
              return stackRef.stackId();
            });
  }

  @AfterEach
  void cleanup() {
    tx(
        () ->
            em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                .setParameter(1, ORG_NAME)
                .executeUpdate());
  }

  @Test
  void distinctKeysProduceMonotonicVersions() {
    StartedUpdate a = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, "k-a", req());
    StartedUpdate b = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, "k-b", req());
    assertEquals(1, a.version());
    assertEquals(2, b.version());
    assertNotEquals(a.updateId(), b.updateId());
  }

  @Test
  void dryRunsDoNotConsumeAppliedVersions() {
    // pulumi preview, and the dry-run pass of up/refresh/destroy (same kind as the apply, but
    // options.dryRun = true), carry version 0 and never advance the applied sequence.
    StartedUpdate preview =
        starter.start(stackId, StackUpdate.Kind.PREVIEW, null, null, null, req());
    StartedUpdate dryRunPass =
        starter.start(
            stackId,
            StackUpdate.Kind.UPDATE,
            null,
            null,
            null,
            new StartUpdateRequest(
                Map.of(), Map.of(), Map.of("dryRun", true), null, null, null, null));
    StartedUpdate apply = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, null, req());

    assertEquals(0, preview.version());
    assertEquals(0, dryRunPass.version());
    assertEquals(1, apply.version());
  }

  @Test
  void startImportAndRenameShareOneAppliedVersionSequence() throws Exception {
    StartedUpdate dryRun =
        starter.start(stackId, StackUpdate.Kind.PREVIEW, null, null, null, req());
    StartedUpdate apply = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, null, req());

    // These operations are terminal before import/rename in the real protocol. Marking them
    // terminal here keeps this test focused on version allocation rather than update completion.
    tx(
        () ->
            StackUpdate.update(
                "status = ?1 where id in (?2, ?3)",
                StackUpdate.Status.SUCCEEDED,
                dryRun.updateId(),
                apply.updateId()));

    UUID importId =
        stackImporter.importDeployment(stackId, null, null, MAPPER.readTree("{\"resources\":[]}"));
    UUID renameId = stackRenamer.rename(stackRef, "renamed", null, null, null);

    assertEquals(0, dryRun.version());
    assertEquals(1, apply.version());
    assertEquals(2, updateVersion(importId));
    assertEquals(3, updateVersion(renameId));
  }

  @Test
  void sameKeyReplayReturnsExistingRow() {
    StartedUpdate first =
        starter.start(stackId, StackUpdate.Kind.PREVIEW, null, null, "dup-key", req());
    StartedUpdate replay =
        starter.start(stackId, StackUpdate.Kind.PREVIEW, null, null, "dup-key", req());
    assertEquals(first.updateId(), replay.updateId());
    assertEquals(first.version(), replay.version());

    tx(
        () -> {
          Number n =
              (Number)
                  em.createNativeQuery(
                          "SELECT count(*) FROM stack_updates WHERE stack_id = ?1"
                              + " AND client_request_id = ?2")
                      .setParameter(1, stackId)
                      .setParameter(2, "dup-key")
                      .getSingleResult();
          assertEquals(1L, n.longValue());
        });
  }

  @Test
  void nullKeyDoesNotDeduplicate() {
    StartedUpdate a = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, null, req());
    StartedUpdate b = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, null, req());
    assertEquals(1, a.version());
    assertEquals(2, b.version());
    assertNotEquals(a.updateId(), b.updateId());
  }

  @Test
  void concurrentStartsSerializeViaForUpdate() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<StartedUpdate> task1 =
          () -> starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, "c-1", req());
      Callable<StartedUpdate> task2 =
          () -> starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, "c-2", req());
      Future<StartedUpdate> f1 = pool.submit(task1);
      Future<StartedUpdate> f2 = pool.submit(task2);
      StartedUpdate r1 = f1.get(10, TimeUnit.SECONDS);
      StartedUpdate r2 = f2.get(10, TimeUnit.SECONDS);

      // Versions are 1 and 2 in some order, never duplicates.
      int min = Math.min(r1.version(), r2.version());
      int max = Math.max(r1.version(), r2.version());
      assertEquals(1, min);
      assertEquals(2, max);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void terminalStatusGuardRejectsRevert() {
    StartedUpdate s = starter.start(stackId, StackUpdate.Kind.UPDATE, null, null, "term", req());

    tx(
        () ->
            em.createNativeQuery("UPDATE stack_updates SET status = 'succeeded' WHERE id = ?1")
                .setParameter(1, s.updateId())
                .executeUpdate());

    PersistenceException ex =
        assertThrows(
            PersistenceException.class,
            () ->
                tx(
                    () ->
                        em.createNativeQuery(
                                "UPDATE stack_updates SET status = 'in-progress' WHERE id = ?1")
                            .setParameter(1, s.updateId())
                            .executeUpdate()));
    String msg = rootMessage(ex);
    assertTrue(
        msg.contains("terminal") || msg.contains("Cannot revert"),
        "expected trigger message, got: " + msg);
  }

  private static StartUpdateRequest req() {
    return new StartUpdateRequest(Map.of(), Map.of(), Map.of(), "desc", "yaml", "v3.234.0", "msg");
  }

  private static int updateVersion(UUID updateId) {
    return read(() -> StackUpdate.<StackUpdate>findById(updateId).version);
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) c = c.getCause();
    return c.getMessage() == null ? "" : c.getMessage();
  }
}
