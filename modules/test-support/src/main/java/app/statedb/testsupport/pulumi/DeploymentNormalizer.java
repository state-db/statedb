package app.statedb.testsupport.pulumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strips the documented-volatile fields from a deployment so two exports of the same state compare
 * equal: {@code manifest.time} (wall clock of assembly) and an empty {@code metadata} object.
 * Everything else — including {@code manifest.magic}, resource ids, and outputs — is meaningful and
 * preserved. Accepts a raw {@code DeploymentV3} or the {@code stack export} envelope.
 */
public final class DeploymentNormalizer {

  private DeploymentNormalizer() {}

  public static JsonNode normalize(JsonNode deploymentOrEnvelope) {
    JsonNode copy = deploymentOrEnvelope.deepCopy();
    JsonNode deployment = copy.has("deployment") ? copy.get("deployment") : copy;
    if (deployment instanceof ObjectNode object) {
      if (object.get("manifest") instanceof ObjectNode manifest) {
        manifest.remove("time");
      }
      JsonNode metadata = object.get("metadata");
      if (metadata != null && metadata.isObject() && metadata.isEmpty()) {
        object.remove("metadata");
      }
    }
    return copy;
  }
}
