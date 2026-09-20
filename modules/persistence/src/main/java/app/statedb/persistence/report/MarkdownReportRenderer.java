package app.statedb.persistence.report;

import app.statedb.persistence.report.ChangeReport.DiagnosticSeverity;
import app.statedb.persistence.report.ChangeReport.Op;
import app.statedb.persistence.report.ChangeReport.PropertyChange;
import app.statedb.persistence.report.ChangeReport.ResourceChange;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ChangeReport} as GitHub-flavoured markdown.
 *
 * <p>Pure: the report and an optional console link in, a {@link RenderedReport} out. The report
 * model is format-agnostic, so a GitLab-flavoured or plain-text renderer is a second implementation
 * rather than a fork of this one.
 *
 * <p><b>Every state-derived fragment is emitted through {@link Markdown#code}.</b> That is the
 * render-time half of the sanitization contract — see {@link Markdown} for why a code span rather
 * than character-class escaping. It applies in the heading too: an org or stack name is as
 * user-chosen as a resource name, and {@code @everyone} autolinks in a heading exactly as it does
 * in a paragraph. The only link emitted is the console link the caller supplied; nothing from state
 * ever becomes a link target.
 *
 * <p><b>No property values.</b> The report does not carry them (see {@link ChangeReport}), so this
 * renderer could not emit one if it tried. Rows are paths and change kinds.
 *
 * <p>{@link RenderedReport#title()} and {@link RenderedReport#summaryLine()} are <em>plain
 * text</em> rather than markdown, because their destinations — a check-run name, a commit status
 * description — do not render markdown. The body re-formats both.
 *
 * <h2>Body budget</h2>
 *
 * <p>The body is assembled section by section and stops when the next section would exceed {@link
 * ReportLimits#maxBodyBytes()}. The cut is deterministic — the same report always produces the same
 * body — and a truncation note plus the {@code truncated} flag say it happened. The note and the
 * console link are reserved out of the budget up front rather than appended hopefully, so the one
 * thing a reader needs in order to see the whole run survives any truncation.
 */
@ApplicationScoped
public class MarkdownReportRenderer {

  private static final String NO_PROPERTIES = "—";
  private static final String LINK_LABEL = "View this run in statedb";
  private static final String TRUNCATION_NOTE =
      "_Truncated: this run is larger than the comment budget. "
          + "Follow the link for the whole of it._";

  /**
   * Renders {@code report}. {@code consoleLink} may be null, in which case no link is emitted; it
   * must otherwise be an absolute http(s) URL that the caller constructed — see {@link
   * Markdown#isEmittableLink}.
   */
  public RenderedReport render(ChangeReport report, URI consoleLink, ReportLimits limits) {
    String title = title(report);
    String summaryLine = summaryLine(report);

    String link =
        Markdown.isEmittableLink(consoleLink) ? Markdown.link(LINK_LABEL, consoleLink) : null;

    boolean linkOmitted =
        link != null && byteLength(TRUNCATION_NOTE) + byteLength(link) + 4 > limits.maxBodyBytes();
    if (linkOmitted) {
      link = null;
    }
    int reserved = byteLength(TRUNCATION_NOTE) + (link == null ? 0 : byteLength(link)) + 4;
    int budget = Math.max(0, limits.maxBodyBytes() - reserved);

    Body body = new Body();
    boolean truncated = report.truncated() || linkOmitted;
    truncated |= !body.append(heading(report), budget);
    truncated |= !body.append("**" + summaryLine + "** · " + Markdown.code(state(report)), budget);

    if (report.isNoOp()) {
      truncated |= !body.append("No resource changes.", budget);
    } else {
      truncated |= !appendTable(body, report, budget);
    }

    String diagnostics = diagnosticsLine(report);
    if (diagnostics != null) {
      truncated |= !body.append(diagnostics, budget);
    }

    if (truncated) {
      body.append(TRUNCATION_NOTE, limits.maxBodyBytes());
    }
    if (link != null) {
      truncated |= !body.append(link, limits.maxBodyBytes());
    }

    return new RenderedReport(title, summaryLine, body.toString(), truncated);
  }

  /**
   * The run's identity in one line, as plain text.
   *
   * <p>A preview has no applied version — it does not consume the sequence — so it is named by kind
   * alone, and an applied run carries {@code v<n>} so two reports for the same stack are told apart
   * at a glance.
   */
  private String title(ChangeReport report) {
    return runLabel(report) + " — " + stackPath(report);
  }

  private String heading(ChangeReport report) {
    String version = report.version() == null ? "" : " v" + report.version();
    return "### Pulumi "
        + Markdown.code(report.kind())
        + version
        + " — "
        + Markdown.code(stackPath(report));
  }

  private static String runLabel(ChangeReport report) {
    return report.version() == null
        ? "Pulumi " + report.kind()
        : "Pulumi " + report.kind() + " v" + report.version();
  }

  private static String stackPath(ChangeReport report) {
    ChangeReport.Coordinates at = report.coordinates();
    return at.org() + "/" + at.project() + "/" + at.stack();
  }

  /**
   * The counts, in one line, compact enough for a commit status description.
   *
   * <p>Counts come from the engine's own summary rather than from the rows below, so the line stays
   * true even when the rows were truncated — and so a run whose events were folded, bounded, or
   * only partially read still reports the right totals.
   */
  private String summaryLine(ChangeReport report) {
    Map<Op, Integer> counts = report.counts();
    List<String> parts = new ArrayList<>();
    addCount(parts, "+", counts.get(Op.CREATE));
    addCount(parts, "~", counts.get(Op.UPDATE));
    addCount(parts, "-", counts.get(Op.DELETE));
    addCount(parts, "±", replaceCount(counts));
    addCount(parts, "=", counts.get(Op.SAME));
    return parts.isEmpty() ? "No changes" : String.join(" ", parts);
  }

  /**
   * How many resources are replaced.
   *
   * <p>The engine reports a replacement either as {@code replace} or as its substeps, depending on
   * the operation, and never as both for the same resource. Taking the maximum of the substep
   * counts rather than their sum is therefore right: {@code create-replacement} and {@code
   * delete-replaced} each count the same resources once. This mirrors what the console already
   * does.
   */
  private static Integer replaceCount(Map<Op, Integer> counts) {
    Integer direct = counts.get(Op.REPLACE);
    if (direct != null && direct > 0) {
      return direct;
    }
    int substeps =
        Math.max(
            zero(counts.get(Op.CREATE_REPLACEMENT)),
            Math.max(zero(counts.get(Op.DELETE_REPLACED)), zero(counts.get(Op.DISCARD_REPLACED))));
    return substeps == 0 ? null : Integer.valueOf(substeps);
  }

  private static int zero(Integer value) {
    return value == null ? 0 : value;
  }

  private static void addCount(List<String> parts, String marker, Integer count) {
    if (count != null && count > 0) {
      parts.add(marker + count);
    }
  }

  /**
   * How the run ended.
   *
   * <p>{@code result} is the engine's own word and is preferred when present. {@code status} is
   * what statedb recorded, and is the only answer for a run that never reported a summary at all —
   * a cancellation, or a run still in progress.
   */
  private static String state(ChangeReport report) {
    return report.result() != null ? report.result() : report.status();
  }

  private boolean appendTable(Body body, ChangeReport report, int budget) {
    if (!body.append("| | Resource | Changed properties |\n| --- | --- | --- |", budget)) {
      return false;
    }
    for (ResourceChange resource : report.resources()) {
      if (!body.append(row(resource), budget)) {
        return false;
      }
    }
    return true;
  }

  private String row(ResourceChange resource) {
    StringBuilder properties = new StringBuilder();
    for (PropertyChange property : resource.properties()) {
      if (properties.length() > 0) {
        properties.append(", ");
      }
      properties.append(Markdown.tableCode(property.path()));
      if (property.kind().forcesReplacement()) {
        properties.append(" (replaces)");
      }
      if (property.secret()) {
        properties.append(" (secret)");
      }
    }
    if (resource.propertiesTruncated()) {
      properties.append(", ").append(ReportText.TRUNCATION_MARKER);
    }
    String cell = properties.length() == 0 ? NO_PROPERTIES : properties.toString();
    return "| "
        + Markdown.tableCode(marker(resource.op()))
        + " | "
        + Markdown.tableCode(resource.type())
        + " "
        + Markdown.tableCode(resource.name())
        + " | "
        + cell
        + " |";
  }

  private static String marker(Op op) {
    return switch (op) {
      case CREATE -> "+";
      case UPDATE -> "~";
      case DELETE -> "-";
      case REPLACE -> "±";
      case REFRESH -> "r";
      case IMPORT -> "i";
      default -> op.wireValue();
    };
  }

  private String diagnosticsLine(ChangeReport report) {
    Integer errors = report.diagnostics().get(DiagnosticSeverity.ERROR);
    Integer warnings = report.diagnostics().get(DiagnosticSeverity.WARNING);
    if (errors == null && warnings == null) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    if (errors != null) {
      parts.add(errors + (errors == 1 ? " error" : " errors"));
    }
    if (warnings != null) {
      parts.add(warnings + (warnings == 1 ? " warning" : " warnings"));
    }
    // Diagnostic *messages* are not rendered. They are engine and provider output, and free text
    // from a provider is the one place a value read out of state can reappear without passing
    // through anything that knows it is a value. Counts say a reader should look; the link says
    // where to look.
    return "**" + String.join(", ", parts) + "** — see the run log.";
  }

  private static int byteLength(CharSequence text) {
    return text.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Accumulates body sections, tracking bytes and block separation.
   *
   * <p>Separation is decided by what came <em>before</em>: two consecutive table lines are one
   * block and take a single newline; anything else takes a blank line. Deciding on the incoming
   * line alone would run a table's header straight onto the preceding paragraph, and GFM would then
   * not see a table at all.
   */
  private static final class Body {

    private final StringBuilder text = new StringBuilder();
    private int bytes;
    private boolean previousWasTableLine;

    boolean append(String section, int budget) {
      if (bytes + byteLength(section) + 2 > budget) {
        return false;
      }
      appendUnbounded(section);
      return true;
    }

    void appendUnbounded(String section) {
      boolean tableLine = section.startsWith("|");
      if (text.length() > 0) {
        text.append('\n');
        if (!(previousWasTableLine && tableLine)) {
          text.append('\n');
        }
      }
      text.append(section);
      bytes = byteLength(text);
      previousWasTableLine = tableLine;
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }
}
