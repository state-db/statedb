package io.statedb.persistence.stack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

final class JournalReplayer {

  private static final Logger LOG = Logger.getLogger(JournalReplayer.class);

  private static final String PULUMI_VERSION = "v3.242.0";

  private final ObjectMapper mapper;
  private final Map<Long, Boolean> toRemove = new HashMap<>();
  private final Map<Long, Boolean> toDeleteInSnapshot = new HashMap<>();
  private final Map<Long, JsonNode> toReplaceInSnapshot = new HashMap<>();
  private final Map<Long, Boolean> markAsDeletion = new HashMap<>();
  private final Map<Long, Boolean> markAsPendingReplacement = new HashMap<>();
  private final Map<Long, Long> operationIDToIndex = new HashMap<>();
  private final Map<Long, JsonNode> incompleteOps = new HashMap<>();
  private boolean hasRefresh = false;
  private long index = 0L;
  private ObjectNode base;
  private final List<JsonNode> newResources = new ArrayList<>();

  JournalReplayer(ObjectMapper mapper, ObjectNode base) {
    this.mapper = mapper;
    this.base = mutableDeployment(base);
  }

  boolean hasNewResources() {
    return !newResources.isEmpty();
  }

  void add(StackAssembler.EntryRow entry) {
    switch (entry.kind()) {
      case 0 -> incompleteOps.put(entry.operationId(), nullable(entry.payload().get("operation")));
      case 1 -> {
        incompleteOps.remove(entry.operationId());
        JsonNode state = entry.payload().get("state");
        if (state != null && !state.isNull()) {
          newResources.add(state.deepCopy());
          operationIDToIndex.put(entry.operationId(), index);
          index++;
        }
        if (entry.removeOld() != null) toDeleteInSnapshot.put(entry.removeOld(), true);
        if (entry.removeNew() != null) toRemove.put(entry.removeNew(), true);
        if (entry.deleteOld() != null) markAsDeletion.put(entry.deleteOld(), true);
        if (entry.deleteNew() != null) mutateNewResource(entry.deleteNew(), "delete", true);
        if (entry.pendingReplacementOld() != null) {
          markAsPendingReplacement.put(entry.pendingReplacementOld(), true);
        }
        if (entry.pendingReplacementNew() != null) {
          mutateNewResource(entry.pendingReplacementNew(), "pendingReplacement", true);
        }
        if (entry.isRefresh()) hasRefresh = true;
      }
      case 2 -> incompleteOps.remove(entry.operationId());
      case 3 -> {
        incompleteOps.remove(entry.operationId());
        hasRefresh = true;
        JsonNode state = entry.payload().get("state");
        if (entry.removeOld() != null) {
          if (state == null || state.isNull()) {
            toDeleteInSnapshot.put(entry.removeOld(), true);
          } else {
            toReplaceInSnapshot.put(entry.removeOld(), state.deepCopy());
          }
        }
        if (entry.removeNew() != null) {
          if (state == null || state.isNull()) {
            toRemove.put(entry.removeNew(), true);
          } else {
            int idx = lookupNewIndex(entry.removeNew(), "RefreshSuccess removeNew");
            if (idx >= 0) newResources.set(idx, state.deepCopy());
          }
        }
      }
      case 4 -> {
        JsonNode state = entry.payload().get("state");
        if (state != null && !state.isNull() && entry.removeOld() != null) {
          toReplaceInSnapshot.put(entry.removeOld(), state.deepCopy());
        }
        if (state != null && !state.isNull() && entry.removeNew() != null) {
          int idx = lookupNewIndex(entry.removeNew(), "Outputs removeNew");
          if (idx >= 0) newResources.set(idx, state.deepCopy());
        }
      }
      case 5 ->
          // Matches Pulumi's JournalReplayer: Write overwrites the base snapshot and is expected
          // before later entries that mutate the snapshot.
          base = mutableDeployment(entry.payload().get("newSnapshot"));
      case 6 -> {
        JsonNode incoming = entry.payload().get("secretsProvider");
        if (sameSecretsProvider(incoming)) return;
        if (incoming == null || incoming.isNull()) {
          base.remove("secrets_providers");
        } else {
          base.set("secrets_providers", incoming.deepCopy());
        }
      }
      case 7 -> {
        base = mutableDeployment(generateDeployment().deployment());
        toRemove.clear();
        toDeleteInSnapshot.clear();
        toReplaceInSnapshot.clear();
        markAsDeletion.clear();
        markAsPendingReplacement.clear();
        operationIDToIndex.clear();
        incompleteOps.clear();
        newResources.clear();
      }
      default ->
          throw new IllegalStateException(
              "stack="
                  + entry.stackId()
                  + " update="
                  + entry.updateId()
                  + " seq="
                  + entry.sequenceId()
                  + " kind="
                  + entry.kind()
                  + ": unknown");
    }
  }

  AssembledDeployment generateDeployment() {
    Set<String> features = new HashSet<>();
    Set<Long> removeIndices = new HashSet<>();
    for (Long opId : toRemove.keySet()) {
      Long resourceIndex = operationIDToIndex.get(opId);
      if (resourceIndex != null) removeIndices.add(resourceIndex);
    }

    ArrayNode resources = mapper.createArrayNode();
    for (int i = 0; i < newResources.size(); i++) {
      if (!removeIndices.contains((long) i)) {
        JsonNode res = newResources.get(i).deepCopy();
        resources.add(res);
        applyFeatures(res, features);
      }
    }

    ArrayNode baseResources = resources(base);
    for (int i = 0; i < baseResources.size(); i++) {
      long baseIndex = i;
      if (toDeleteInSnapshot.containsKey(baseIndex)) continue;

      ObjectNode res = objectCopy(baseResources.get(i));
      if (markAsPendingReplacement.containsKey(baseIndex)) {
        res.put("pendingReplacement", true);
      }

      JsonNode replacement = toReplaceInSnapshot.get(baseIndex);
      if (replacement != null) {
        JsonNode copy = replacement.deepCopy();
        resources.add(copy);
        applyFeatures(copy, features);
      } else {
        if (markAsDeletion.containsKey(baseIndex)) res.put("delete", true);
        resources.add(res);
        applyFeatures(res, features);
      }
    }

    ArrayNode operations = mapper.createArrayNode();
    for (JsonNode op : incompleteOps.values()) {
      if (op != null && !op.isNull()) {
        operations.add(op.deepCopy());
        JsonNode resource = op.get("resource");
        if (resource != null) applyFeatures(resource, features);
      }
    }
    JsonNode basePending = base.get("pending_operations");
    if (basePending != null && basePending.isArray()) {
      for (JsonNode op : basePending) {
        if ("creating".equals(text(op.get("type")))) {
          operations.add(op.deepCopy());
          JsonNode resource = op.get("resource");
          if (resource != null) applyFeatures(resource, features);
        }
      }
    }

    if (hasRefresh) rebuildDependencies(resources);

    ObjectNode deployment = mapper.createObjectNode();
    JsonNode secretsProviders = base.get("secrets_providers");
    if (secretsProviders != null && !secretsProviders.isNull()) {
      deployment.set("secrets_providers", secretsProviders.deepCopy());
    }
    deployment.set("resources", resources);
    if (!operations.isEmpty()) deployment.set("pending_operations", operations);
    JsonNode metadata = base.get("metadata");
    if (metadata != null && !metadata.isNull()) deployment.set("metadata", metadata.deepCopy());
    deployment.set("manifest", manifest());

    normalizeURNReferences(deployment);

    List<String> sortedFeatures = new ArrayList<>(features);
    Collections.sort(sortedFeatures);
    return new AssembledDeployment(deployment, sortedFeatures.isEmpty() ? 3 : 4, sortedFeatures);
  }

  private ObjectNode mutableDeployment(JsonNode node) {
    ObjectNode deployment =
        node != null && node.isObject() ? (ObjectNode) node.deepCopy() : mapper.createObjectNode();
    if (!deployment.has("resources") || !deployment.get("resources").isArray()) {
      deployment.set("resources", mapper.createArrayNode());
    }
    return deployment;
  }

  private ArrayNode resources(ObjectNode deployment) {
    JsonNode resources = deployment.get("resources");
    return resources != null && resources.isArray()
        ? (ArrayNode) resources
        : mapper.createArrayNode();
  }

  private void mutateNewResource(Long operationId, String field, boolean value) {
    int i = lookupNewIndex(operationId, "Success mutate " + field);
    if (i < 0) return;
    ObjectNode resource = objectCopy(newResources.get(i));
    resource.put(field, value);
    newResources.set(i, resource);
  }

  // Soft-fail rather than throw: opIDs are scoped to the replayer's lifetime, and
  // RebuiltBaseState (kind 7) clears operationIDToIndex. A malformed journal that
  // references a pre-rebuild opID after a rebuild would otherwise crash an export
  // of a stack whose journal Pulumi already accepted. StackAssembler logs the
  // out-of-order RebuiltBaseState case at a higher level.
  private int lookupNewIndex(Long operationId, String context) {
    Long i = operationIDToIndex.get(operationId);
    if (i == null) {
      LOG.warnf("%s: unknown resource operation ID %s; skipping", context, operationId);
      return -1;
    }
    return Math.toIntExact(i);
  }

  private ObjectNode objectCopy(JsonNode node) {
    if (node == null || !node.isObject()) {
      return mapper.createObjectNode();
    }
    return (ObjectNode) node.deepCopy();
  }

  private boolean sameSecretsProvider(JsonNode incoming) {
    JsonNode current = base.get("secrets_providers");
    boolean currentMissing = current == null || current.isNull();
    boolean incomingMissing = incoming == null || incoming.isNull();
    if (currentMissing && incomingMissing) return true;
    if (currentMissing || incomingMissing) return false;
    return nodeEquals(current.get("type"), incoming.get("type"))
        && nodeEquals(current.get("state"), incoming.get("state"));
  }

  private ObjectNode manifest() {
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("time", Instant.now().toString());
    manifest.put("magic", sha256Hex(PULUMI_VERSION));
    manifest.put("version", PULUMI_VERSION);
    return manifest;
  }

  private void rebuildDependencies(ArrayNode resources) {
    Set<String> referenceable = new HashSet<>();
    for (JsonNode resourceNode : resources) {
      ObjectNode resource = (ObjectNode) resourceNode;
      ArrayNode dependencies = mapper.createArrayNode();
      JsonNode oldDeps = resource.get("dependencies");
      if (oldDeps != null && oldDeps.isArray()) {
        for (JsonNode dep : oldDeps) {
          if (referenceable.contains(dep.asText())) dependencies.add(dep.asText());
        }
        resource.set("dependencies", dependencies);
      }

      ObjectNode propertyDependencies = mapper.createObjectNode();
      JsonNode oldPropDeps = resource.get("propertyDependencies");
      if (oldPropDeps != null && oldPropDeps.isObject()) {
        oldPropDeps
            .fields()
            .forEachRemaining(
                entry -> {
                  ArrayNode values = mapper.createArrayNode();
                  if (entry.getValue().isArray()) {
                    for (JsonNode dep : entry.getValue()) {
                      if (referenceable.contains(dep.asText())) values.add(dep.asText());
                    }
                  }
                  propertyDependencies.set(entry.getKey(), values);
                });
        resource.set("propertyDependencies", propertyDependencies);
      }

      String deletedWith = text(resource.get("deletedWith"));
      if (!deletedWith.isEmpty() && !referenceable.contains(deletedWith)) {
        resource.put("deletedWith", "");
      }
      JsonNode replaceWith = resource.get("replaceWith");
      if (replaceWith != null && replaceWith.isArray()) {
        ArrayNode rewritten = mapper.createArrayNode();
        for (JsonNode urn : replaceWith) {
          rewritten.add(referenceable.contains(urn.asText()) ? urn.asText() : "");
        }
        resource.set("replaceWith", rewritten);
      }

      String urn = text(resource.get("urn"));
      if (!urn.isEmpty()) referenceable.add(urn);
    }

    undangleParentResources(referenceable, resources);
  }

  private void undangleParentResources(Set<String> undeleted, ArrayNode resources) {
    Map<String, String> availableParents = new HashMap<>();
    for (JsonNode resourceNode : resources) {
      ObjectNode resource = (ObjectNode) resourceNode;
      String parent = text(resource.get("parent"));
      if (!parent.isEmpty() && !undeleted.contains(parent)) {
        resource.put("parent", availableParents.getOrDefault(parent, ""));
      }
      availableParents.put(text(resource.get("urn")), text(resource.get("parent")));
    }
  }

  private void normalizeURNReferences(ObjectNode deployment) {
    ArrayNode resources = resources(deployment);
    Map<String, String> aliased = new HashMap<>();
    for (JsonNode resourceNode : resources) {
      String urn = text(resourceNode.get("urn"));
      JsonNode aliases = resourceNode.get("aliases");
      if (aliases != null && aliases.isArray()) {
        for (JsonNode alias : aliases) {
          String old = aliased.putIfAbsent(alias.asText(), urn);
          if (old != null && !old.equals(urn)) {
            throw new IllegalStateException(
                "two resources ('"
                    + old
                    + "' and '"
                    + urn
                    + "') have the same alias: "
                    + alias.asText());
          }
        }
      }
      String parent = text(resourceNode.get("parent"));
      if (aliased.containsKey(parent) && !aliased.get(parent).isEmpty()) {
        String parentUrn = aliased.get(parent);
        String newUrn = urnWithParentType(urn, parentUrn);
        if (!newUrn.equals(urn)) aliased.put(urn, newUrn);
      }
    }

    for (JsonNode resourceNode : resources) {
      ObjectNode resource = (ObjectNode) resourceNode;
      resource.put("urn", fixURN(text(resource.get("urn")), aliased));
      String parent = text(resource.get("parent"));
      if (!parent.isEmpty()) resource.put("parent", fixURN(parent, aliased));
      String provider = text(resource.get("provider"));
      if (!provider.isEmpty()) resource.put("provider", fixProvider(provider, aliased));
      rewriteURNArray(resource, "dependencies", aliased);
      rewritePropertyDependencies(resource, aliased);
      rewriteURNField(resource, "deletedWith", aliased);
      rewriteURNField(resource, "viewOf", aliased);
      resource.remove("aliases");
    }
  }

  private void rewriteURNArray(ObjectNode resource, String field, Map<String, String> aliased) {
    JsonNode values = resource.get(field);
    if (values == null || !values.isArray()) return;
    ArrayNode rewritten = mapper.createArrayNode();
    for (JsonNode value : values) rewritten.add(fixURN(value.asText(), aliased));
    resource.set(field, rewritten);
  }

  private void rewritePropertyDependencies(ObjectNode resource, Map<String, String> aliased) {
    JsonNode values = resource.get("propertyDependencies");
    if (values == null || !values.isObject()) return;
    ObjectNode rewritten = mapper.createObjectNode();
    values
        .fields()
        .forEachRemaining(
            entry -> {
              ArrayNode deps = mapper.createArrayNode();
              if (entry.getValue().isArray()) {
                for (JsonNode dep : entry.getValue()) deps.add(fixURN(dep.asText(), aliased));
              }
              rewritten.set(entry.getKey(), deps);
            });
    resource.set("propertyDependencies", rewritten);
  }

  private void rewriteURNField(ObjectNode resource, String field, Map<String, String> aliased) {
    String value = text(resource.get(field));
    if (!value.isEmpty()) resource.put(field, fixURN(value, aliased));
  }

  private String fixProvider(String provider, Map<String, String> aliased) {
    int sep = provider.lastIndexOf("::");
    if (sep < 0) return provider;
    return fixURN(provider.substring(0, sep), aliased) + provider.substring(sep);
  }

  private String fixURN(String urn, Map<String, String> aliased) {
    return aliased.getOrDefault(urn, urn);
  }

  private String urnWithParentType(String urn, String parentUrn) {
    String parentType = qualifiedType(parentUrn);
    if (parentType.isEmpty() || parentType.equals("pulumi:pulumi:Stack")) return urn;
    int typeStart = nthIndexOf(urn, "::", 2);
    int nameStart = urn.lastIndexOf("::");
    if (typeStart < 0 || nameStart <= typeStart) return urn;
    String type = urn.substring(typeStart + 2, nameStart);
    int childTypeStart = type.lastIndexOf('$');
    String childType = childTypeStart >= 0 ? type.substring(childTypeStart + 1) : type;
    return urn.substring(0, typeStart + 2)
        + parentType
        + "$"
        + childType
        + urn.substring(nameStart);
  }

  private String qualifiedType(String urn) {
    int typeStart = nthIndexOf(urn, "::", 2);
    int nameStart = urn.lastIndexOf("::");
    if (typeStart < 0 || nameStart <= typeStart) return "";
    return urn.substring(typeStart + 2, nameStart);
  }

  private int nthIndexOf(String value, String needle, int n) {
    int from = 0;
    for (int i = 0; i < n; i++) {
      int found = value.indexOf(needle, from);
      if (found < 0) return -1;
      from = found + needle.length();
      if (i == n - 1) return found;
    }
    return -1;
  }

  private void applyFeatures(JsonNode resource, Set<String> features) {
    if (bool(resource.get("refreshBeforeUpdate"))) features.add("refresh-before-update");
    if (!text(resource.get("viewOf")).isEmpty()) features.add("views");
    JsonNode hooks = resource.get("resourceHooks");
    if (hooks != null && hooks.isObject() && hooks.size() > 0) features.add("hooks");
    if (bool(resource.get("taint"))) features.add("taint");
    JsonNode replaceWith = resource.get("replaceWith");
    if (replaceWith != null && replaceWith.isArray() && !replaceWith.isEmpty())
      features.add("replace-with");
  }

  private static JsonNode nullable(JsonNode node) {
    return node == null || node.isNull() ? null : node.deepCopy();
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText("");
  }

  private static boolean bool(JsonNode node) {
    return node != null && !node.isNull() && node.asBoolean(false);
  }

  private static boolean nodeEquals(JsonNode left, JsonNode right) {
    if (left == null || left.isNull()) return right == null || right.isNull();
    return left.equals(right);
  }

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
