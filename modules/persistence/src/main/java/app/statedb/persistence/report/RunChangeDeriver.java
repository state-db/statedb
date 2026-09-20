package app.statedb.persistence.report;

import app.statedb.persistence.events.StoredEvent;
import app.statedb.persistence.report.ChangeReport.DiffKind;
import app.statedb.persistence.report.ChangeReport.Op;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Converts Pulumi engine events into the one change-set model used by every presentation surface.
 *
 * <p>Pure: callers supply an already-consistent event stream from {@code UpdateEventReader}. No
 * renderer is allowed to reinterpret raw step metadata independently.
 */
@ApplicationScoped
public class RunChangeDeriver {

  private static final List<String> STEP_KINDS =
      List.of("resourcePre", "resOutputs", "resOpFailed");

  public RunChanges derive(
      List<StoredEvent> events, int maxSecretScanDepth, boolean standaloneRefresh) {
    Map<String, StepRow> steps = new LinkedHashMap<>();
    JsonNode stackMetadata = null;
    List<StoredEvent> ordered = new ArrayList<>(events == null ? List.of() : events);
    ordered.sort(Comparator.comparingInt(StoredEvent::sequence));

    for (StoredEvent event : ordered) {
      if (!STEP_KINDS.contains(event.kind()) || event.payload() == null) {
        continue;
      }
      JsonNode metadata = event.payload().path(event.kind() + "Event").path("metadata");
      if (metadata.isObject()) {
        accept(steps, event.sequence(), metadata, standaloneRefresh);
        if ("resOutputs".equals(event.kind())
            && "pulumi:pulumi:Stack".equals(textOrNull(metadata.path("type")))) {
          stackMetadata = metadata;
        }
      }
    }

    List<RunChanges.ResourceChange> changed = new ArrayList<>();
    List<RunChanges.ResourceChange> unchanged = new ArrayList<>();
    List<RunChanges.ResourceChange> activity = new ArrayList<>();
    for (StepRow row : steps.values()) {
      RunChanges.ResourceChange change = build(row, maxSecretScanDepth);
      if (row.op.isChange() && (row.op != Op.REFRESH || !change.properties().isEmpty())) {
        changed.add(change);
      } else if (row.op == Op.SAME) {
        unchanged.add(change);
      } else {
        activity.add(change);
      }
    }
    return new RunChanges(
        changed, unchanged, activity, outputChanges(stackMetadata, maxSecretScanDepth));
  }

  private static List<RunChanges.OutputChange> outputChanges(
      JsonNode metadata, int maxSecretScanDepth) {
    if (metadata == null) {
      return List.of();
    }
    JsonNode oldOutputs = metadata.path("old").path("outputs");
    JsonNode newOutputs = metadata.path("new").path("outputs");
    boolean hadPriorOutputs = oldOutputs.isObject() && !oldOutputs.isEmpty();
    Set<String> keys = new TreeSet<>();
    if (oldOutputs.isObject()) {
      oldOutputs.fieldNames().forEachRemaining(keys::add);
    }
    if (newOutputs.isObject()) {
      newOutputs.fieldNames().forEachRemaining(keys::add);
    }
    List<RunChanges.OutputChange> outputs = new ArrayList<>();
    for (String key : keys) {
      JsonNode before = oldOutputs.get(key);
      JsonNode after = newOutputs.get(key);
      String kind;
      if (!hadPriorOutputs || java.util.Objects.equals(before, after)) {
        kind = "same";
      } else if (before == null) {
        kind = "add";
      } else if (after == null) {
        kind = "remove";
      } else {
        kind = "update";
      }
      outputs.add(
          new RunChanges.OutputChange(
              key,
              kind,
              before != null,
              SecretSentinel.redact(before, maxSecretScanDepth),
              after != null,
              SecretSentinel.redact(after, maxSecretScanDepth)));
    }
    return outputs;
  }

  private static void accept(
      Map<String, StepRow> steps, int sequence, JsonNode metadata, boolean standaloneRefresh) {
    String urn = textOrEmpty(metadata.path("urn"));
    if (urn.isEmpty()) {
      return;
    }
    Op rawOp = Op.fromWire(textOrNull(metadata.path("op")));
    Op op = rawOp.isReplacementSubstep() ? Op.REPLACE : rawOp;
    StepRow existing = steps.get(urn);
    JsonNode diff = hasDiffInfo(metadata) ? metadata : existing == null ? null : existing.diff;

    if (existing != null && existing.op == Op.REFRESH && op == Op.SAME) {
      // During preview/up --refresh, refresh is a preliminary phase and terminal `same` is the
      // program diff result. Preserve a drift diff as a change, or the no-diff refresh as activity.
      // A standalone refresh instead treats `same` as its terminal outcome.
      if (!standaloneRefresh || hasPropertyDiff(existing.diff)) {
        op = Op.REFRESH;
      }
    }
    steps.put(
        urn,
        new StepRow(
            existing == null ? sequence : existing.sequence,
            urn,
            textOrEmpty(metadata.path("type")),
            op,
            diff == null ? metadata : diff));
  }

  private static RunChanges.ResourceChange build(StepRow row, int maxSecretScanDepth) {
    List<RunChanges.PropertyChange> properties =
        rawProperties(row.diff).stream()
            .map(property -> property(row.diff, property, maxSecretScanDepth))
            .toList();
    return new RunChanges.ResourceChange(
        row.sequence, row.urn, row.type, resourceName(row.urn), row.op.wireValue(), properties);
  }

  private static RunChanges.PropertyChange property(
      JsonNode metadata, RawProperty property, int maxSecretScanDepth) {
    JsonNode oldState = metadata.path("old");
    JsonNode newState = metadata.path("new");
    JsonNode oldValues =
        !property.detailed || property.inputDiff
            ? oldState.path("inputs")
            : oldState.path("outputs");
    JsonNode newValues = newState.path("inputs");
    PropertyPath parsed = PropertyPath.parse(property.path);
    JsonNode before = parsed == null ? null : parsed.resolve(oldValues);
    JsonNode after = parsed == null ? null : parsed.resolve(newValues);
    boolean resolved = before != null || after != null;
    boolean secret =
        resolved
            ? SecretSentinel.containsSecret(before, maxSecretScanDepth)
                || SecretSentinel.containsSecret(after, maxSecretScanDepth)
            : SecretSentinel.containsSecret(oldState, maxSecretScanDepth)
                || SecretSentinel.containsSecret(newState, maxSecretScanDepth);
    return new RunChanges.PropertyChange(
        property.path,
        property.kind.wireValue(),
        isInternal(property.path),
        secret,
        before != null,
        SecretSentinel.redact(before, maxSecretScanDepth),
        after != null,
        SecretSentinel.redact(after, maxSecretScanDepth));
  }

  private static List<RawProperty> rawProperties(JsonNode metadata) {
    List<RawProperty> properties = new ArrayList<>();
    JsonNode detailed = metadata.path("detailedDiff");
    if (detailed.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = detailed.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        JsonNode value = entry.getValue();
        properties.add(
            new RawProperty(
                entry.getKey(),
                DiffKind.fromWire(textOrNull(value.path("diffKind"))),
                value.path("inputDiff").asBoolean(false),
                true));
      }
    } else {
      JsonNode coarse = metadata.path("diffs");
      if (coarse.isArray()) {
        for (JsonNode path : coarse) {
          properties.add(new RawProperty(path.asText(""), DiffKind.UNKNOWN, true, false));
        }
      }
    }
    List<String> hidden = hiddenPaths(metadata);
    properties.removeIf(property -> property.path.isEmpty() || isHidden(property.path, hidden));
    properties.sort(Comparator.comparing(RawProperty::path));
    return properties;
  }

  private static List<String> hiddenPaths(JsonNode metadata) {
    List<String> hidden = new ArrayList<>();
    for (String side : List.of("old", "new")) {
      JsonNode paths = metadata.path(side).path("hideDiffs");
      if (paths.isArray()) {
        for (JsonNode path : paths) {
          if (path.isTextual() && !path.asText().isEmpty()) {
            hidden.add(path.asText());
          }
        }
      }
    }
    return hidden;
  }

  private static boolean isHidden(String path, List<String> hidden) {
    return hidden.stream()
        .anyMatch(
            prefix ->
                path.equals(prefix)
                    || path.startsWith(prefix + ".")
                    || path.startsWith(prefix + "["));
  }

  private static boolean isInternal(String path) {
    for (String segment : path.split("[.\\[]")) {
      if (segment.startsWith("__")) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDiffInfo(JsonNode metadata) {
    return metadata != null
        && (metadata.path("detailedDiff").isObject() || metadata.path("diffs").isArray());
  }

  private static boolean hasPropertyDiff(JsonNode metadata) {
    return metadata != null && !rawProperties(metadata).isEmpty();
  }

  private static String resourceName(String urn) {
    int last = urn.lastIndexOf("::");
    return last < 0 ? urn : urn.substring(last + 2);
  }

  private static String textOrNull(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static String textOrEmpty(JsonNode node) {
    String value = textOrNull(node);
    return value == null ? "" : value;
  }

  private record StepRow(int sequence, String urn, String type, Op op, JsonNode diff) {}

  private record RawProperty(String path, DiffKind kind, boolean inputDiff, boolean detailed) {}
}
