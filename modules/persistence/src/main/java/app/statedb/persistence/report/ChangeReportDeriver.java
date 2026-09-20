package app.statedb.persistence.report;

import app.statedb.persistence.events.StoredEvent;
import app.statedb.persistence.report.ChangeReport.Coordinates;
import app.statedb.persistence.report.ChangeReport.DiagnosticSeverity;
import app.statedb.persistence.report.ChangeReport.DiffKind;
import app.statedb.persistence.report.ChangeReport.Op;
import app.statedb.persistence.report.ChangeReport.PropertyChange;
import app.statedb.persistence.report.ChangeReport.ResourceChange;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ChangeReport} from an update and its engine events.
 *
 * <p><b>Pure.</b> Events arrive as a parameter and nothing here queries the database. That is not a
 * style preference: {@code EventArchiveFolder} folds a terminal run's events into {@code
 * update_event_archives} and deletes the non-summary rows, <em>leaving the summary rows
 * behind</em>. A derivation that queried {@code update_events} itself would therefore not fail
 * loudly on an archived run — it would return a summary-only report, silently, for every run older
 * than the fold grace period. {@link app.statedb.persistence.events.UpdateEventReader} is the seam
 * that reads the right source, and it is the caller's job to have used it.
 *
 * <p>Sanitization happens here, at construction, not as a later pass: see {@link ReportText} for
 * what that means for a string and {@link ChangeReport} for what the resulting surface guarantees.
 *
 * <p>Diff semantics follow {@code design/research/pulumi-diff-mechanism.md}. In particular a coarse
 * {@code diffs} list is a top-level inclusion filter over an input-to-input comparison, and is
 * <em>not</em> licence to compare old outputs against new inputs — which is why a path whose kind
 * the provider did not state is reported as {@link DiffKind#UNKNOWN} rather than inferred from
 * value presence.
 */
@ApplicationScoped
public class ChangeReportDeriver {

  private static final String SUMMARY = "summary";
  private static final String DIAGNOSTIC = "diagnostic";
  private static final List<String> STEP_KINDS =
      List.of("resourcePre", "resOutputs", "resOpFailed");

  /**
   * Derives the report for {@code update} from {@code events}.
   *
   * <p>{@code events} need not be sorted and need not be complete — a run still in progress, or one
   * whose stream was bounded by the reader, derives what it has.
   */
  public ChangeReport derive(
      StackRef ref, StackUpdate update, List<StoredEvent> events, ReportLimits limits) {
    Map<String, StepRow> steps = new LinkedHashMap<>();
    Map<Op, Integer> counts = new EnumMap<>(Op.class);
    Map<DiagnosticSeverity, Integer> diagnostics = new EnumMap<>(DiagnosticSeverity.class);
    String result = null;
    boolean truncated = false;

    List<StoredEvent> ordered = new ArrayList<>(events == null ? List.of() : events);
    ordered.sort(Comparator.comparingInt(StoredEvent::sequence));

    for (StoredEvent event : ordered) {
      String kind = event.kind();
      JsonNode payload = event.payload();
      if (payload == null) {
        continue;
      }
      if (SUMMARY.equals(kind)) {
        JsonNode summary = payload.path("summaryEvent");
        result = textOrNull(summary.path("result"));
        readCounts(summary.path("resourceChanges"), counts);
      } else if (DIAGNOSTIC.equals(kind)) {
        String severity = textOrNull(payload.path("diagnosticEvent").path("severity"));
        if (severity != null) {
          diagnostics.merge(DiagnosticSeverity.fromWire(severity), 1, Integer::sum);
        }
      } else if (STEP_KINDS.contains(kind)) {
        JsonNode metadata = payload.path(kind + "Event").path("metadata");
        if (metadata.isObject()) {
          accept(steps, metadata);
        }
      }
    }

    List<ResourceChange> resources = new ArrayList<>();
    List<StepRow> changed = steps.values().stream().filter(row -> row.op.isChange()).toList();
    for (StepRow row : changed) {
      if (resources.size() == limits.maxResources()) {
        truncated = true;
        break;
      }
      Built built = build(row, limits);
      truncated |= built.truncated();
      resources.add(built.change());
    }

    ReportText.Text org = ReportText.neutralize(ref.orgName(), limits.maxFieldCodePoints());
    ReportText.Text project = ReportText.neutralize(ref.projectName(), limits.maxFieldCodePoints());
    ReportText.Text stack = ReportText.neutralize(ref.stackName(), limits.maxFieldCodePoints());
    truncated |= org.truncated() || project.truncated() || stack.truncated();

    ReportText.Text engineResult = ReportText.neutralize(result, limits.maxFieldCodePoints());

    return new ChangeReport(
        update.stackId,
        update.id,
        update.dryRun ? null : Integer.valueOf(update.version),
        new Coordinates(org.value(), project.value(), stack.value()),
        update.kind.wireValue(),
        update.status.wireValue(),
        update.dryRun,
        engineResult.isEmpty() ? null : engineResult.value(),
        counts,
        resources,
        diagnostics,
        update.startedAt,
        update.endedAt,
        truncated);
  }

  /**
   * Folds one step event into the row for its URN.
   *
   * <p>A resource is visited more than once — {@code resourcePre} then {@code resOutputs} or {@code
   * resOpFailed} — and the later visit is authoritative for the op while not always repeating the
   * diff. So the op is overwritten and the diff is only overwritten when the later event actually
   * carries one; otherwise a completed resource would lose the property list its pre-event
   * announced. The first-seen sequence is kept so rows stay in engine order.
   */
  private void accept(Map<String, StepRow> steps, JsonNode metadata) {
    String urn = metadata.path("urn").asText("");
    if (urn.isEmpty()) {
      return;
    }
    Op op = Op.fromWire(textOrNull(metadata.path("op")));
    // Replacement decomposes into create-replacement/delete-replaced substeps against the same
    // URN. Collapsing them keeps one row per resource saying "replace", which is what a reader
    // wants; without it a replaced resource appears twice, once as a create and once as a delete.
    if (op.isReplacementSubstep()) {
      op = Op.REPLACE;
    }
    StepRow existing = steps.get(urn);
    JsonNode diffSource =
        hasDiffInfo(metadata) ? metadata : existing == null ? null : existing.diff;
    // Re-putting an existing key does not move it in a LinkedHashMap, so rows stay in the order
    // the engine first visited each resource. That is why the events are sorted by sequence
    // before this loop, and why no separate ordering key is carried here.
    steps.put(
        urn,
        new StepRow(
            urn, metadata.path("type").asText(""), op, diffSource == null ? metadata : diffSource));
  }

  private static boolean hasDiffInfo(JsonNode metadata) {
    return metadata.path("detailedDiff").isObject() || metadata.path("diffs").isArray();
  }

  private Built build(StepRow row, ReportLimits limits) {
    ReportText.Text urn = ReportText.neutralize(row.urn, limits.maxFieldCodePoints());
    ReportText.Text type = ReportText.neutralize(row.type, limits.maxFieldCodePoints());
    ReportText.Text name =
        ReportText.neutralize(resourceName(row.urn), limits.maxFieldCodePoints());

    List<PropertyChange> properties = new ArrayList<>();
    boolean propertiesTruncated = false;
    boolean fieldTruncated = urn.truncated() || type.truncated() || name.truncated();

    for (RawProperty raw : rawProperties(row.diff)) {
      if (properties.size() == limits.maxPropertiesPerResource()) {
        propertiesTruncated = true;
        break;
      }
      ReportText.Text path = ReportText.neutralize(raw.path(), limits.maxFieldCodePoints());
      fieldTruncated |= path.truncated();
      properties.add(
          new PropertyChange(path.value(), raw.kind(), isSecretAt(row.diff, raw, limits)));
    }

    return new Built(
        new ResourceChange(
            urn.value(), type.value(), name.value(), row.op, properties, propertiesTruncated),
        fieldTruncated || propertiesTruncated);
  }

  /**
   * The changed property paths on this step, sorted by path so a report is diffable run to run.
   *
   * <p>{@code detailedDiff} wins when present, including when it is present and <em>empty</em>:
   * Pulumi's API type deliberately omits {@code omitempty} there because {@code null} and {@code
   * {}} mean different things — no detailed diff, versus a provider stating there are no detailed
   * paths. The coarse {@code diffs} list is the fallback, and it only names top-level keys, so its
   * entries carry no kind.
   */
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
    properties.removeIf(property -> property.path().isEmpty());
    properties.removeIf(property -> isHidden(property.path(), hiddenPaths(metadata)));
    properties.sort(Comparator.comparing(RawProperty::path));
    return properties;
  }

  /**
   * Property paths the provider asked not to be displayed.
   *
   * <p>{@code hideDiffs} lives on {@code StepEventStateMetadata} — so on {@code metadata.old} and
   * {@code metadata.new}, not on the step itself — and is Pulumi's own "do not show a diff here".
   * The console honours it, and a report with a wider audience than the console has strictly more
   * reason to. Both sides are read: a delete carries only {@code old}, a create only {@code new},
   * and a path hidden on either is hidden.
   */
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

  /**
   * Whether {@code path} is hidden, directly or as a descendant of a hidden path.
   *
   * <p>Hiding {@code credentials} must hide {@code credentials.token} and {@code credentials[0]}
   * with it; a prefix that hid {@code credentialsPublic} as well would be wrong, so the boundary
   * character is required.
   */
  private static boolean isHidden(String path, List<String> hidden) {
    for (String prefix : hidden) {
      if (path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "[")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the value at this path, on either side, is or contains a Pulumi secret.
   *
   * <p>Which "old" side a path refers to is the provider's choice and is carried per path as {@code
   * inputDiff}: true compares old inputs to new inputs, false compares old <em>outputs</em> to new
   * inputs. A coarse {@code diffs} entry is an input-to-input filter and so reads old inputs. This
   * is the same selection {@code TranslateDetailedDiff} makes and the console already applies;
   * treating a missing {@code inputDiff} as false would read a bag the provider never pointed at.
   *
   * <p><b>Fails safe.</b> When the path cannot be parsed or does not resolve — a wildcard, a
   * malformed key, a provider naming a path that is not in the state the event carried — the answer
   * falls back to whether the resource's state contains a secret anywhere. A false "this is a
   * secret" costs a reader nothing here, because no value is emitted either way; a false "this is
   * not" would be a claim we cannot support.
   */
  private static boolean isSecretAt(JsonNode metadata, RawProperty property, ReportLimits limits) {
    JsonNode oldState = metadata.path("old");
    JsonNode newState = metadata.path("new");
    JsonNode oldValues =
        !property.detailed() || property.inputDiff()
            ? oldState.path("inputs")
            : oldState.path("outputs");
    JsonNode newValues = newState.path("inputs");

    PropertyPath path = PropertyPath.parse(property.path());
    if (path == null) {
      return containsSecret(oldState, newState, limits);
    }
    JsonNode before = path.resolve(oldValues);
    JsonNode after = path.resolve(newValues);
    if (before == null && after == null) {
      return containsSecret(oldState, newState, limits);
    }
    return SecretSentinel.containsSecret(before, limits.maxSecretScanDepth())
        || SecretSentinel.containsSecret(after, limits.maxSecretScanDepth());
  }

  private static boolean containsSecret(JsonNode oldState, JsonNode newState, ReportLimits limits) {
    return SecretSentinel.containsSecret(oldState, limits.maxSecretScanDepth())
        || SecretSentinel.containsSecret(newState, limits.maxSecretScanDepth());
  }

  private static void readCounts(JsonNode resourceChanges, Map<Op, Integer> counts) {
    if (!resourceChanges.isObject()) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = resourceChanges.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      JsonNode value = entry.getValue();
      if (value.isNumber()) {
        counts.merge(Op.fromWire(entry.getKey()), value.asInt(), Integer::sum);
      }
    }
  }

  /** The last {@code ::}-delimited URN segment, which is the resource's own name. */
  private static String resourceName(String urn) {
    int last = urn.lastIndexOf("::");
    return last < 0 ? urn : urn.substring(last + 2);
  }

  private static String textOrNull(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private record StepRow(String urn, String type, Op op, JsonNode diff) {}

  private record RawProperty(String path, DiffKind kind, boolean inputDiff, boolean detailed) {}

  private record Built(ResourceChange change, boolean truncated) {}
}
