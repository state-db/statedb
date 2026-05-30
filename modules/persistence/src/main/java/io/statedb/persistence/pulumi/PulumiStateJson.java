package io.statedb.persistence.pulumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Helpers for normalizing Pulumi deployment JSON before durable storage. */
public final class PulumiStateJson {

  private PulumiStateJson() {}

  /**
   * Returns a deep copy with Pulumi resource {@code stackTrace} fields removed.
   *
   * <p>Pulumi's file/S3 DIY backend persists full checkpoints and history checkpoint copies, but
   * does not persist service event batches. In state-db the journal is also durable and later
   * compacted into snapshots, so source stack traces get retained multiple times without being
   * needed for replay. Keep {@code sourcePosition}; drop only the bulky call-frame arrays.
   */
  public static JsonNode withoutStackTraces(JsonNode raw) {
    if (raw == null) {
      return null;
    }
    JsonNode copy = raw.deepCopy();
    removeStackTraces(copy);
    return copy;
  }

  private static void removeStackTraces(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node instanceof ObjectNode object) {
      object.remove("stackTrace");
      object.elements().forEachRemaining(PulumiStateJson::removeStackTraces);
      return;
    }
    if (node instanceof ArrayNode array) {
      array.elements().forEachRemaining(PulumiStateJson::removeStackTraces);
    }
  }
}
