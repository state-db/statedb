package app.statedb.persistence.report;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one update changed, sanitized, as a value object.
 *
 * <p>This is the output of derivation and the input to every renderer, and it is the boundary
 * "Invariant 2" draws: <b>no raw {@code JsonNode} appears on this surface</b>. Everything here has
 * been through {@link ReportText#neutralize}, or is a closed enum, or is a number. A renderer
 * cannot leak a value it was never given, and a renderer added later inherits the guarantee rather
 * than having to re-earn it.
 *
 * <p><b>Property values are not carried at all.</b> Only paths and change kinds. That is the
 * values-never-emitted default from the design, and it means the secret question mostly does not
 * arise: {@link PropertyChange#secret()} records <em>that</em> a path held a secret, which is
 * metadata a reader wants and the Pulumi CLI itself shows, without the value.
 *
 * @param stackId the stack this run belongs to, for a caller assembling a console link
 * @param updateId the run
 * @param version applied-update version, or null for a dry run, which has none
 * @param coordinates org/project/stack names, sanitized — they are user-chosen too
 * @param kind the Pulumi operation kind ({@code preview}, {@code update}, ...)
 * @param status the run's terminal (or in-progress) status
 * @param dryRun whether this was a preview pass
 * @param result the engine's own result from its summary event, absent when the run has no summary
 * @param counts resource counts by operation, from {@code summaryEvent.resourceChanges}
 * @param resources the resources that changed, in engine order
 * @param diagnostics counts of diagnostic events by severity
 * @param startedAt when the run started
 * @param endedAt when it ended, or null while in progress
 * @param truncated whether anything was dropped for length at any level
 */
public record ChangeReport(
    UUID stackId,
    UUID updateId,
    Integer version,
    Coordinates coordinates,
    String kind,
    String status,
    boolean dryRun,
    String result,
    Map<Op, Integer> counts,
    List<ResourceChange> resources,
    Map<DiagnosticSeverity, Integer> diagnostics,
    Instant startedAt,
    Instant endedAt,
    boolean truncated) {

  public ChangeReport {
    kind = ReportText.requireNeutralized(kind, ReportLimits.MAX_FIELD_CODE_POINTS, "kind");
    status = ReportText.requireNeutralized(status, ReportLimits.MAX_FIELD_CODE_POINTS, "status");
    result = ReportText.requireNeutralized(result, ReportLimits.MAX_FIELD_CODE_POINTS, "result");
    counts = counts == null ? Map.of() : Map.copyOf(counts);
    resources = resources == null ? List.of() : List.copyOf(resources);
    diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
  }

  /**
   * The same report with {@code truncated} set.
   *
   * <p>For a caller that learns of a loss the derivation could not see: the event reader bounds how
   * much of a stream it returns, so a report derived from a bounded read is complete as far as it
   * goes and still owes the reader the flag.
   */
  public ChangeReport withTruncated(boolean value) {
    return value == truncated
        ? this
        : new ChangeReport(
            stackId,
            updateId,
            version,
            coordinates,
            kind,
            status,
            dryRun,
            result,
            counts,
            resources,
            diagnostics,
            startedAt,
            endedAt,
            value);
  }

  /** Whether the run changed nothing. Drives the "no changes" body rather than an empty table. */
  public boolean isNoOp() {
    return resources.isEmpty();
  }

  /** Where the run lives, sanitized. All three are operator- or user-chosen names. */
  public record Coordinates(String org, String project, String stack) {

    public Coordinates {
      org = ReportText.requireNeutralized(org, ReportLimits.MAX_FIELD_CODE_POINTS, "org");
      project =
          ReportText.requireNeutralized(project, ReportLimits.MAX_FIELD_CODE_POINTS, "project");
      stack = ReportText.requireNeutralized(stack, ReportLimits.MAX_FIELD_CODE_POINTS, "stack");
    }
  }

  /**
   * One resource the run touched.
   *
   * @param urn the full URN, sanitized
   * @param type the Pulumi type token, sanitized
   * @param name the resource's own name — the last URN segment — sanitized
   * @param op what the engine did, or is going to do, to it
   * @param properties the changed property paths, sorted, bounded
   * @param propertiesTruncated whether more paths changed than are carried here
   */
  public record ResourceChange(
      String urn,
      String type,
      String name,
      Op op,
      List<PropertyChange> properties,
      boolean propertiesTruncated) {

    public ResourceChange {
      urn = ReportText.requireNeutralized(urn, ReportLimits.MAX_FIELD_CODE_POINTS, "urn");
      type = ReportText.requireNeutralized(type, ReportLimits.MAX_FIELD_CODE_POINTS, "type");
      name = ReportText.requireNeutralized(name, ReportLimits.MAX_FIELD_CODE_POINTS, "name");
      properties = properties == null ? List.of() : List.copyOf(properties);
    }
  }

  /**
   * One changed property path.
   *
   * @param path the property path as the provider reported it, sanitized. A path may itself name a
   *     secret property; paths are emitted, only values are withheld.
   * @param kind how it changed
   * @param secret whether the value at this path — on either side — was a Pulumi secret. Carried
   *     because a reader wants to know a secret rotated without being told what it rotated to.
   */
  public record PropertyChange(String path, DiffKind kind, boolean secret) {

    public PropertyChange {
      path = ReportText.requireNeutralized(path, ReportLimits.MAX_FIELD_CODE_POINTS, "path");
    }
  }

  /** Diagnostic severity values declared by Pulumi's engine-event API. */
  public enum DiagnosticSeverity {
    INFO("info"),
    INFO_ERR("info#err"),
    WARNING("warning"),
    ERROR("error"),
    UNKNOWN("unknown");

    private final String wireValue;

    DiagnosticSeverity(String wireValue) {
      this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
      return wireValue;
    }

    public static DiagnosticSeverity fromWire(String value) {
      if (value != null) {
        for (DiagnosticSeverity severity : values()) {
          if (severity.wireValue.equals(value)) {
            return severity;
          }
        }
      }
      return UNKNOWN;
    }
  }

  /**
   * A Pulumi step operation.
   *
   * <p>A closed enum rather than the raw string, and that is a sanitization property, not a
   * convenience: an op read from an event can never carry injected text into a renderer, because a
   * value outside this set becomes {@link #UNKNOWN}. Members and wire values are from {@code
   * pkg/resource/deploy/step.go} at Pulumi v3.259.0.
   */
  public enum Op {
    SAME("same"),
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    REPLACE("replace"),
    CREATE_REPLACEMENT("create-replacement"),
    DELETE_REPLACED("delete-replaced"),
    READ("read"),
    READ_REPLACEMENT("read-replacement"),
    REFRESH("refresh"),
    DISCARD("discard"),
    DISCARD_REPLACED("discard-replaced"),
    REMOVE_PENDING_REPLACE("remove-pending-replace"),
    IMPORT("import"),
    IMPORT_REPLACEMENT("import-replacement"),
    DIFF("diff"),
    EXTEND_PARAMETERIZE("extend-parameterize"),
    /** An op this build does not know. A newer CLI's addition, or a malformed event. */
    UNKNOWN("unknown");

    private final String wireValue;

    Op(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static Op fromWire(String value) {
      if (value != null) {
        for (Op op : values()) {
          if (op.wireValue.equals(value)) {
            return op;
          }
        }
      }
      return UNKNOWN;
    }

    /** Whether this op represents an actual change, as opposed to a no-op or a read. */
    public boolean isChange() {
      return switch (this) {
        case SAME, READ, READ_REPLACEMENT, DIFF, EXTEND_PARAMETERIZE, UNKNOWN -> false;
        default -> true;
      };
    }

    /** Whether this op is one of the substeps a replacement decomposes into. */
    public boolean isReplacementSubstep() {
      return switch (this) {
        case CREATE_REPLACEMENT,
            DELETE_REPLACED,
            DISCARD_REPLACED,
            READ_REPLACEMENT,
            IMPORT_REPLACEMENT ->
            true;
        default -> false;
      };
    }
  }

  /**
   * How one property changed. Wire values are {@code apitype.DiffKind} at Pulumi v3.259.0.
   *
   * <p>Closed for the same reason as {@link Op}.
   */
  public enum DiffKind {
    ADD("add"),
    ADD_REPLACE("add-replace"),
    DELETE("delete"),
    DELETE_REPLACE("delete-replace"),
    UPDATE("update"),
    UPDATE_REPLACE("update-replace"),
    /** No detailed diff said how it changed — only that it did. */
    UNKNOWN("unknown");

    private final String wireValue;

    DiffKind(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static DiffKind fromWire(String value) {
      if (value != null) {
        for (DiffKind kind : values()) {
          if (kind.wireValue.equals(value)) {
            return kind;
          }
        }
      }
      return UNKNOWN;
    }

    /** Whether this change forces the resource to be replaced. */
    public boolean forcesReplacement() {
      return this == ADD_REPLACE || this == DELETE_REPLACE || this == UPDATE_REPLACE;
    }
  }
}
