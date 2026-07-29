package io.statedb.app.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.statedb.persistence.events.UpdateEvent;
import io.statedb.persistence.events.UpdateEventView;
import io.statedb.testsupport.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Real Pulumi engine events, taken from the recorder fixtures, for tests that need a realistic
 * event corpus rather than a synthetic one. Compression ratios only mean something against the
 * actual shape of the data — the repeated URNs, provider references, and old/new property bags
 * across consecutive resource events are what block compression exploits.
 */
public final class EventArchiveFixture {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXTURES_ROOT = "tools/pulumi/recorder/fixtures/by-command";

  /** Every recorded engine event, in fixture order. */
  public static List<JsonNode> recordedEvents() {
    List<JsonNode> events = new ArrayList<>();
    try (Stream<Path> commands = Files.list(RepoRoot.get().resolve(FIXTURES_ROOT))) {
      List<Path> batches = new ArrayList<>();
      for (Path command : commands.sorted().toList()) {
        try (Stream<Path> files = Files.list(command)) {
          files
              .filter(p -> p.getFileName().toString().endsWith("_events_batch.json"))
              .sorted()
              .forEach(batches::add);
        }
      }
      for (Path batch : batches) {
        MAPPER.readTree(batch.toFile()).path("body").path("events").forEach(events::add);
      }
    } catch (Exception e) {
      throw new IllegalStateException("failed to read recorder event fixtures", e);
    }
    if (events.isEmpty()) {
      throw new IllegalStateException("no recorded events under " + FIXTURES_ROOT);
    }
    return events;
  }

  /**
   * Persists {@code count} events for an update, cycling the recorded corpus and re-stamping
   * sequences so they stay unique. Returns the number persisted.
   */
  public static int persistEvents(UUID stackId, UUID updateId, List<JsonNode> corpus, int count) {
    for (int sequence = 0; sequence < count; sequence++) {
      ObjectNode raw = ((ObjectNode) corpus.get(sequence % corpus.size()).deepCopy());
      raw.put("sequence", sequence);
      UpdateEventView view = new UpdateEventView(raw);
      UpdateEvent event = new UpdateEvent();
      event.stackId = stackId;
      event.updateId = updateId;
      event.sequence = sequence;
      event.kind = view.kind();
      event.severity = view.severity();
      event.payload = raw;
      event.persist();
    }
    return count;
  }

  private EventArchiveFixture() {}
}
