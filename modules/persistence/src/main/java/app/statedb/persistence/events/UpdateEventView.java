package app.statedb.persistence.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Typed accessors over one raw engine event JsonNode. The wire shape is:
 *
 * <pre>{
 *   "sequence": 5,
 *   "timestamp": 0,
 *   "&lt;kind&gt;Event": { ... payload ... }
 * }</pre>
 *
 * <p>{@link #kind()} returns the envelope key with the trailing {@code "Event"} stripped (e.g.
 * {@code "diagnosticEvent"} → {@code "diagnostic"}). {@link #severity()} is read from the
 * diagnostic payload when present, otherwise {@code null}. {@link #payload()} returns the entire
 * raw event verbatim so we don't drop fields the CLI sent that we don't yet model.
 */
public final class UpdateEventView {

  private final JsonNode raw;

  public UpdateEventView(JsonNode raw) {
    if (raw == null || raw.isNull()) {
      throw new IllegalArgumentException("event entry is null");
    }
    if (!raw.isObject()) {
      throw new IllegalArgumentException(
          "event entry must be a JSON object, got " + raw.getNodeType());
    }
    this.raw = raw;
  }

  public int sequence() {
    JsonNode n = raw.get("sequence");
    if (n == null || n.isNull()) {
      throw new IllegalArgumentException("event missing required field 'sequence'");
    }
    if (!n.canConvertToInt()) {
      throw new IllegalArgumentException("event 'sequence' must fit in a 32-bit signed int");
    }
    int seq = n.asInt();
    if (seq < 0) {
      throw new IllegalArgumentException("event 'sequence' must be non-negative, got " + seq);
    }
    return seq;
  }

  /** Envelope key minus the trailing {@code "Event"}. Exactly one such key must be present. */
  public String kind() {
    String found = null;
    Iterator<Map.Entry<String, JsonNode>> it = raw.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String name = e.getKey();
      if ("sequence".equals(name) || "timestamp".equals(name)) {
        continue;
      }
      if (!name.endsWith("Event")) {
        continue;
      }
      if (found != null) {
        throw new IllegalArgumentException(
            "event has multiple kind envelopes: " + found + "Event and " + name);
      }
      found = name.substring(0, name.length() - "Event".length());
    }
    if (found == null) {
      throw new IllegalArgumentException("event missing kind envelope (no *Event field)");
    }
    return found;
  }

  /**
   * For {@code diagnosticEvent}: the {@code severity} (info/warning/error). Null for every other
   * kind. Returns null rather than throwing if the diagnostic envelope is missing severity — the
   * partial index only fires on warning/error, so missing values just fall outside it.
   */
  public String severity() {
    JsonNode diag = raw.get("diagnosticEvent");
    if (diag == null || !diag.isObject()) {
      return null;
    }
    JsonNode s = diag.get("severity");
    return (s == null || s.isNull()) ? null : s.asText();
  }

  /**
   * The raw event node, stored verbatim in {@code payload}. Unlike journal entries, we don't strip
   * the promoted scalars ({@code sequence}, {@code timestamp}) — events have only one promoted
   * column and the round-trip cost (~12 bytes/row in JSONB) is worth the simpler replay path.
   */
  public JsonNode payload() {
    return raw;
  }
}
