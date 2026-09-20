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
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Projects the canonical {@link RunChanges} model into the smaller, value-free report model.
 *
 * <p>Event-to-diff semantics belong to {@link RunChangeDeriver}. This class owns only report
 * sanitization, report budgets, summary counts, and diagnostic counts.
 */
@ApplicationScoped
public class ChangeReportDeriver {

  private final RunChangeDeriver changeDeriver = new RunChangeDeriver();

  public ChangeReport derive(
      StackRef ref, StackUpdate update, List<StoredEvent> events, ReportLimits limits) {
    Map<Op, Integer> counts = new EnumMap<>(Op.class);
    Map<DiagnosticSeverity, Integer> diagnostics = new EnumMap<>(DiagnosticSeverity.class);
    String result = null;
    for (StoredEvent event : events == null ? List.<StoredEvent>of() : events) {
      JsonNode payload = event.payload();
      if (payload == null) {
        continue;
      }
      if ("summary".equals(event.kind())) {
        JsonNode summary = payload.path("summaryEvent");
        result = textOrNull(summary.path("result"));
        readCounts(summary.path("resourceChanges"), counts);
      } else if ("diagnostic".equals(event.kind())) {
        String severity = textOrNull(payload.path("diagnosticEvent").path("severity"));
        if (severity != null) {
          diagnostics.merge(DiagnosticSeverity.fromWire(severity), 1, Integer::sum);
        }
      }
    }

    RunChanges changes =
        changeDeriver.derive(
            events, limits.maxSecretScanDepth(), update.kind == StackUpdate.Kind.REFRESH);
    List<ResourceChange> resources = new ArrayList<>();
    boolean truncated = false;
    for (RunChanges.ResourceChange raw : changes.changed()) {
      if (resources.size() == limits.maxResources()) {
        truncated = true;
        break;
      }
      Built built = build(raw, limits);
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

  private static Built build(RunChanges.ResourceChange raw, ReportLimits limits) {
    ReportText.Text urn = ReportText.neutralize(raw.urn(), limits.maxFieldCodePoints());
    ReportText.Text type = ReportText.neutralize(raw.type(), limits.maxFieldCodePoints());
    ReportText.Text name = ReportText.neutralize(raw.name(), limits.maxFieldCodePoints());
    boolean truncated = urn.truncated() || type.truncated() || name.truncated();
    List<PropertyChange> properties = new ArrayList<>();
    boolean propertiesTruncated = false;
    for (RunChanges.PropertyChange rawProperty : raw.properties()) {
      if (properties.size() == limits.maxPropertiesPerResource()) {
        propertiesTruncated = true;
        break;
      }
      ReportText.Text path = ReportText.neutralize(rawProperty.path(), limits.maxFieldCodePoints());
      truncated |= path.truncated();
      properties.add(
          new PropertyChange(
              path.value(), DiffKind.fromWire(rawProperty.kind()), rawProperty.secret()));
    }
    return new Built(
        new ResourceChange(
            urn.value(),
            type.value(),
            name.value(),
            Op.fromWire(raw.op()),
            properties,
            propertiesTruncated),
        truncated || propertiesTruncated);
  }

  private static void readCounts(JsonNode resourceChanges, Map<Op, Integer> counts) {
    if (!resourceChanges.isObject()) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = resourceChanges.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (entry.getValue().isNumber()) {
        counts.merge(Op.fromWire(entry.getKey()), entry.getValue().asInt(), Integer::sum);
      }
    }
  }

  private static String textOrNull(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private record Built(ResourceChange change, boolean truncated) {}
}
