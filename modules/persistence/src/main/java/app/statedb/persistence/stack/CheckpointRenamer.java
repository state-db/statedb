package app.statedb.persistence.stack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Rewrites the stack-name (and project) identity baked into a Pulumi {@code DeploymentV3} when a
 * stack is renamed. The Pulumi cloud backend delegates the whole rename to the server in a single
 * call, so the equivalent of the diy backend's local {@code edit.RenameStack} is our job:
 *
 * <ul>
 *   <li><b>Resource URNs.</b> A URN is {@code urn:pulumi:<stack>::<project>::<type>::<name>}; the
 *       stack and project components are name strings, not our row UUIDs, so a metadata-only move
 *       leaves them stale. We rewrite every URN-bearing field on every resource (and pending
 *       operation), mirroring {@code edit.RenameStack}. The root {@code pulumi:pulumi:Stack}
 *       resource additionally carries its name as {@code <project>-<stack>}, so its name component
 *       is regenerated too.
 *   <li><b>Service secrets provider.</b> The {@code service} secrets provider rebuilds its decrypt
 *       endpoint from {@code secrets_providers.state.{owner,project,stack}} (see Pulumi's {@code
 *       NewServiceSecretsManagerFromState}). Without rewriting it, every post-rename {@code
 *       batch-decrypt} call targets the old stack path and 404s. Owner cannot change via a rename,
 *       so only project/stack are touched.
 * </ul>
 */
@ApplicationScoped
public class CheckpointRenamer {

  private static final String URN_PREFIX = "urn:pulumi:";
  private static final String ROOT_STACK_TYPE = "pulumi:pulumi:Stack";

  /** Mutates {@code deployment} in place to the new stack/project identity. */
  public void rewrite(ObjectNode deployment, String newStack, String newProject) {
    JsonNode resources = deployment.get("resources");
    if (resources instanceof ArrayNode array) {
      for (JsonNode resource : array) {
        if (resource instanceof ObjectNode object) {
          rewriteResource(object, newStack, newProject);
        }
      }
    }

    JsonNode pending = deployment.get("pending_operations");
    if (pending instanceof ArrayNode array) {
      for (JsonNode operation : array) {
        if (operation.get("resource") instanceof ObjectNode object) {
          rewriteResource(object, newStack, newProject);
        }
      }
    }

    rewriteSecretsProvider(deployment, newStack, newProject);
  }

  private void rewriteResource(ObjectNode resource, String newStack, String newProject) {
    rewriteUrnField(resource, "urn", newStack, newProject);
    rewriteUrnField(resource, "parent", newStack, newProject);
    rewriteUrnField(resource, "deletedWith", newStack, newProject);
    rewriteUrnArray(resource, "dependencies", newStack, newProject);
    rewriteUrnArray(resource, "replaceWith", newStack, newProject);
    rewritePropertyDependencies(resource, newStack, newProject);
    rewriteProvider(resource, newStack, newProject);
  }

  private void rewriteUrnField(
      ObjectNode resource, String field, String newStack, String newProject) {
    String value = text(resource.get(field));
    if (!value.isEmpty()) {
      resource.put(field, rewriteUrn(value, newStack, newProject));
    }
  }

  private void rewriteUrnArray(
      ObjectNode resource, String field, String newStack, String newProject) {
    if (!(resource.get(field) instanceof ArrayNode values)) {
      return;
    }
    ArrayNode rewritten = resource.arrayNode();
    for (JsonNode value : values) {
      rewritten.add(rewriteUrn(value.asText(), newStack, newProject));
    }
    resource.set(field, rewritten);
  }

  private void rewritePropertyDependencies(
      ObjectNode resource, String newStack, String newProject) {
    if (!(resource.get("propertyDependencies") instanceof ObjectNode dependencies)) {
      return;
    }
    ObjectNode rewritten = resource.objectNode();
    dependencies
        .fields()
        .forEachRemaining(
            entry -> {
              ArrayNode deps = resource.arrayNode();
              if (entry.getValue() instanceof ArrayNode values) {
                for (JsonNode value : values) {
                  deps.add(rewriteUrn(value.asText(), newStack, newProject));
                }
              }
              rewritten.set(entry.getKey(), deps);
            });
    resource.set("propertyDependencies", rewritten);
  }

  // A provider reference is `<providerURN>::<providerID>`; the id has no `::`, so the last `::`
  // separates the URN to rewrite from the trailing id.
  private void rewriteProvider(ObjectNode resource, String newStack, String newProject) {
    String provider = text(resource.get("provider"));
    if (provider.isEmpty()) {
      return;
    }
    int separator = provider.lastIndexOf("::");
    if (separator < 0) {
      return;
    }
    resource.put(
        "provider",
        rewriteUrn(provider.substring(0, separator), newStack, newProject)
            + provider.substring(separator));
  }

  private void rewriteSecretsProvider(ObjectNode deployment, String newStack, String newProject) {
    if (!(deployment.get("secrets_providers") instanceof ObjectNode provider)) {
      return;
    }
    if (!"service".equals(text(provider.get("type")))) {
      return;
    }
    if (provider.get("state") instanceof ObjectNode state) {
      state.put("project", newProject);
      state.put("stack", newStack);
    }
  }

  // urn:pulumi:<stack>::<project>::<type>::<name> — swap the first two components. Names may
  // contain `::`, so we keep everything from the project separator onward verbatim for normal
  // resources rather than re-splitting on the name. The root stack resource's name is the
  // derived `<project>-<stack>`, so it is regenerated.
  private static String rewriteUrn(String urn, String newStack, String newProject) {
    if (urn == null || !urn.startsWith(URN_PREFIX)) {
      return urn;
    }
    int firstSeparator = urn.indexOf("::", URN_PREFIX.length());
    if (firstSeparator < 0) {
      return urn;
    }
    int secondSeparator = urn.indexOf("::", firstSeparator + 2);
    if (secondSeparator < 0) {
      return urn;
    }

    String afterProject = urn.substring(secondSeparator + 2);
    int nameSeparator = afterProject.lastIndexOf("::");
    String qualifiedType = nameSeparator >= 0 ? afterProject.substring(0, nameSeparator) : "";
    if (ROOT_STACK_TYPE.equals(qualifiedType)) {
      return URN_PREFIX
          + newStack
          + "::"
          + newProject
          + "::"
          + ROOT_STACK_TYPE
          + "::"
          + newProject
          + "-"
          + newStack;
    }
    return URN_PREFIX + newStack + "::" + newProject + urn.substring(secondSeparator);
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText("");
  }
}
