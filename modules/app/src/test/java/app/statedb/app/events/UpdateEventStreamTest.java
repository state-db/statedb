package app.statedb.app.events;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.events.EventArchiveFolder;
import app.statedb.persistence.events.UpdateEventArchive;
import app.statedb.persistence.events.UpdateEventReader;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link UpdateEventReader#stream}, the report-oriented read.
 *
 * <p>What it owes a caller over {@link UpdateEventReader#page}: the whole stream in one decode, the
 * same answer whether the run is folded or live, and an explicit bound that reports itself rather
 * than silently shortening the stream.
 */
@QuarkusTest
class UpdateEventStreamTest {

  private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

  @Inject UpdateEventReader reader;
  @Inject EventArchiveFolder folder;

  private String org;

  @AfterEach
  @Transactional
  void cleanup() {
    if (org == null) {
      return;
    }
    Organization.delete("name", org);
    org = null;
  }

  @Test
  void readsTheWholeStreamFromRowsAndFromAnArchiveAlike() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("event-stream-parity");
    UUID updateId = seedUpdate(stackId, corpus, corpus.size());

    UpdateEventReader.EventStream live = read(() -> reader.stream(stackId, updateId, 10_000));
    assertFalse(live.archived());
    assertFalse(live.truncated());
    assertEquals(corpus.size(), live.events().size());
    // The page reader is the established answer for the same window; the stream must agree.
    assertEquals(read(() -> reader.page(stackId, updateId, -1, 10_000).events()), live.events());

    foldRun(updateId);
    assertNotNull(read(() -> UpdateEventArchive.<UpdateEventArchive>findById(updateId)));

    UpdateEventReader.EventStream archived = read(() -> reader.stream(stackId, updateId, 10_000));
    assertTrue(archived.archived(), "the archive must be the source once folded");
    assertEquals(live.events(), archived.events(), "folding must not change what a reader sees");
  }

  @TestTransaction
  @Test
  void theBoundTruncatesAndSaysSo() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("event-stream-bound");
    UUID updateId = seedUpdate(stackId, corpus, 50);

    UpdateEventReader.EventStream bounded = reader.stream(stackId, updateId, 10);
    assertTrue(bounded.truncated(), "a bound that cut the stream must report it");
    assertEquals(10, bounded.events().size());
    // A prefix, in sequence order — a caller deriving from it gets the start of the run, not a
    // scattering of it.
    assertEquals(
        reader.page(stackId, updateId, -1, 10).events(),
        bounded.events(),
        "the bounded read must be the first N events");
  }

  /** A stream that exactly fills the bound is complete; saying otherwise would be a false alarm. */
  @TestTransaction
  @Test
  void aStreamThatExactlyFillsTheBoundIsNotTruncated() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("event-stream-exact");
    UUID updateId = seedUpdate(stackId, corpus, 25);

    UpdateEventReader.EventStream exact = reader.stream(stackId, updateId, 25);
    assertFalse(exact.truncated());
    assertEquals(25, exact.events().size());
  }

  @Test
  void theBoundIsAlsoHonouredOnAnArchivedRun() {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    UUID stackId = seedStack("event-stream-bound-archived");
    UUID updateId = seedUpdate(stackId, corpus, 50);
    foldRun(updateId);

    UpdateEventReader.EventStream bounded = read(() -> reader.stream(stackId, updateId, 10));
    assertTrue(bounded.archived());
    assertTrue(bounded.truncated());
    assertEquals(10, bounded.events().size());
  }

  @TestTransaction
  @Test
  void anUnknownUpdateIsAnEmptyStreamRatherThanAnError() {
    UUID stackId = seedStack("event-stream-missing");
    UpdateEventReader.EventStream none = reader.stream(stackId, UUID.randomUUID(), 10);
    assertEquals(List.of(), none.events());
    assertFalse(none.archived());
    assertFalse(none.truncated());
  }

  /**
   * An unbounded read is the mistake this method exists to make impossible to write by accident.
   */
  @Test
  void rejectsANonPositiveBound() {
    UUID stackId = seedStack("event-stream-bad-bound");
    UUID updateId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class, () -> read(() -> reader.stream(stackId, updateId, 0)));
    assertThrows(
        IllegalArgumentException.class, () -> read(() -> reader.stream(stackId, updateId, -1)));
  }

  private UUID seedStack(String orgName) {
    this.org = orgName + "-" + System.nanoTime();
    String name = this.org;
    return read(
        () -> {
          Organization organization = new Organization();
          organization.name = name;
          organization.displayName = name;
          organization.persist();
          Project project = new Project();
          project.orgId = organization.id;
          project.name = "project";
          project.persist();
          Workspace workspace = new Workspace();
          workspace.orgId = organization.id;
          workspace.kind = Workspace.Kind.pulumi;
          workspace.persist();
          Stack stack = new Stack();
          stack.orgId = organization.id;
          stack.projectId = project.id;
          stack.workspaceId = workspace.id;
          stack.name = "dev";
          stack.persistAndFlush();
          return stack.id;
        });
  }

  private UUID seedUpdate(UUID stackId, List<JsonNode> corpus, int eventCount) {
    return read(
        () -> {
          StackUpdate update = new StackUpdate();
          update.stackId = stackId;
          update.kind = StackUpdate.Kind.UPDATE;
          update.status = StackUpdate.Status.SUCCEEDED;
          update.version = 1;
          update.startedAt = LONG_AGO;
          update.endedAt = LONG_AGO;
          update.persistAndFlush();
          EventArchiveFixture.persistEvents(stackId, update.id, corpus, eventCount);
          return update.id;
        });
  }

  private void foldRun(UUID updateId) {
    tx(() -> folder.fold(updateId, Instant.now()));
  }
}
