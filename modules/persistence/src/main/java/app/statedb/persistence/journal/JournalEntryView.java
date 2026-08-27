package app.statedb.persistence.journal;

import app.statedb.persistence.pulumi.PulumiStateJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/**
 * Typed accessors over one raw journal entry JsonNode. The promoted fields (sequenceID, kind,
 * operationID, version, isRefresh, six index fields) become columns; everything else is preserved
 * in {@link #payload()} for replay.
 */
public final class JournalEntryView {

  private static final Set<String> PROMOTED =
      Set.of(
          "sequenceID",
          "kind",
          "operationID",
          "version",
          "isRefresh",
          "removeOld",
          "removeNew",
          "deleteOld",
          "deleteNew",
          "pendingReplacementOld",
          "pendingReplacementNew");

  private final JsonNode raw;

  public JournalEntryView(JsonNode raw) {
    this.raw = raw;
  }

  public long sequenceId() {
    JsonNode n = raw.get("sequenceID");
    if (n == null || n.isNull()) {
      throw new IllegalArgumentException("journal entry missing required field 'sequenceID'");
    }
    return n.asLong();
  }

  public short kind() {
    JsonNode n = raw.get("kind");
    if (n == null || n.isNull()) {
      throw new IllegalArgumentException("journal entry missing required field 'kind'");
    }
    return (short) n.asInt();
  }

  public Long operationId() {
    JsonNode n = raw.get("operationID");
    return (n == null || n.isNull()) ? null : n.asLong();
  }

  public int version() {
    JsonNode n = raw.get("version");
    return (n == null || n.isNull()) ? 1 : n.asInt();
  }

  public boolean isRefresh() {
    JsonNode n = raw.get("isRefresh");
    return n != null && !n.isNull() && n.asBoolean(false);
  }

  public Long removeOld() {
    return longOrNull("removeOld");
  }

  public Long removeNew() {
    return longOrNull("removeNew");
  }

  public Long deleteOld() {
    return longOrNull("deleteOld");
  }

  public Long deleteNew() {
    return longOrNull("deleteNew");
  }

  public Long pendingReplacementOld() {
    return longOrNull("pendingReplacementOld");
  }

  public Long pendingReplacementNew() {
    return longOrNull("pendingReplacementNew");
  }

  /** The original entry node minus promoted columns and bulky source stack traces. */
  public JsonNode payload() {
    ObjectNode copy = (ObjectNode) PulumiStateJson.withoutStackTraces(raw);
    for (String f : PROMOTED) {
      copy.remove(f);
    }
    return copy;
  }

  /**
   * Denormalized URN: kind 0 (Begin) reads from {@code operation.resource.urn}; kinds 1/3/4
   * (Success/RefreshSuccess/Outputs) read from {@code state.urn}; everything else returns null.
   * Matches the table's {@code resource_urn} column rules from {@code db-schema.md}.
   */
  public String resourceUrn() {
    short k = kind();
    if (k == 0) {
      JsonNode op = raw.get("operation");
      if (op == null) return null;
      JsonNode res = op.get("resource");
      if (res == null) return null;
      JsonNode urn = res.get("urn");
      return (urn == null || urn.isNull()) ? null : urn.asText();
    }
    if (k == 1 || k == 3 || k == 4) {
      JsonNode state = raw.get("state");
      if (state == null) return null;
      JsonNode urn = state.get("urn");
      return (urn == null || urn.isNull()) ? null : urn.asText();
    }
    return null;
  }

  private Long longOrNull(String field) {
    JsonNode n = raw.get(field);
    return (n == null || n.isNull()) ? null : n.asLong();
  }
}
