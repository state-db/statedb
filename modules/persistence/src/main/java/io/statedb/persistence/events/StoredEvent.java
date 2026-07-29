package io.statedb.persistence.events;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One engine event as readers consume it, independent of whether it came from an {@code
 * update_events} row or from an {@link UpdateEventArchive} blob. These four fields are the whole of
 * what the console events endpoint serves, so they are also exactly what {@link EventArchiveCodec}
 * must round-trip.
 */
public record StoredEvent(int sequence, String kind, String severity, JsonNode payload) {

  public static StoredEvent of(UpdateEvent row) {
    return new StoredEvent(row.sequence, row.kind, row.severity, row.payload);
  }
}
