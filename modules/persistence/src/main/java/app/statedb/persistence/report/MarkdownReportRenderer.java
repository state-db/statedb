package app.statedb.persistence.report;

import app.statedb.persistence.report.ChangeReport.Bucket;
import app.statedb.persistence.report.ChangeReport.CountSource;
import app.statedb.persistence.report.ChangeReport.DiagnosticSeverity;
import app.statedb.persistence.report.ChangeReport.Op;
import app.statedb.persistence.report.ChangeReport.PropertyChange;
import app.statedb.persistence.report.ChangeReport.ResourceChange;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link ChangeReport} as GitHub-flavoured markdown.
 *
 * <p>Pure: the report and an optional console link in, a {@link RenderedReport} out. The report
 * model is format-agnostic, so a GitLab-flavoured or plain-text renderer is a second implementation
 * rather than a fork of this one.
 *
 * <p><b>Every state-derived fragment is emitted as code.</b> Headings and counts use {@link
 * Markdown#code}; resource lines use a fenced {@link Markdown#diffBlock} so GitHub applies its
 * native diff colors. That is the render-time half of the sanitization contract — see {@link
 * Markdown} for why code rather than character-class escaping. The only link emitted is the console
 * link the caller supplied; nothing from state ever becomes a link target.
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
 * <p>The body is assembled section by section, and a section that would exceed {@link
 * ReportLimits#maxBodyBytes()} is skipped. The cut is deterministic — the same report always
 * produces the same body — and a truncation note plus the {@code truncated} flag say it happened.
 * The note and the console link are reserved out of the budget up front rather than appended
 * hopefully, so the one thing a reader needs in order to see the whole run survives any truncation.
 */
@ApplicationScoped
public class MarkdownReportRenderer {

  private static final String SEPARATOR = "\n\n";
  private static final int SEPARATOR_BYTES = byteLength(SEPARATOR);
  private static final String PLAN_HEADING = "**Plan:**\n\n";
  private static final int PLAN_HEADING_BYTES = byteLength(PLAN_HEADING);
  private static final String TRUNCATION_NOTE =
      "_Truncated: this comment omits some run details. Follow the link for the whole run._";

  /**
   * Renders {@code report}. {@code consoleLink} may be null, in which case no link is emitted; it
   * must otherwise be an absolute http(s) URL that the caller constructed — see {@link
   * Markdown#isEmittableLink}.
   */
  public RenderedReport render(ChangeReport report, URI consoleLink, ReportLimits limits) {
    String title = title(report);
    String summaryLine = summaryLine(report);

    String link =
        Markdown.isEmittableLink(consoleLink)
            ? "**" + Markdown.link(linkLabel(report), consoleLink) + "**"
            : null;

    boolean linkOmitted = link != null && tailBytes(link) > limits.maxBodyBytes();
    if (linkOmitted) {
      link = null;
    }
    int budget = Math.max(0, limits.maxBodyBytes() - tailBytes(link));

    Body body = new Body();
    boolean truncated = report.truncated() || linkOmitted;
    truncated |= !body.append(heading(report), budget);
    truncated |= !body.append(stateLine(report), budget);

    if (report.isNoOp()) {
      truncated |= !body.append("No resource changes.", budget);
    } else {
      String operations = operationSummary(report);
      if (operations != null) {
        truncated |= !body.append(operations, budget);
      }
      truncated |= !appendResources(body, report, budget);
    }

    String diagnostics = diagnosticsLine(report);
    if (diagnostics != null) {
      truncated |= !body.append(diagnostics, budget);
    }

    if (truncated) {
      body.append(TRUNCATION_NOTE, limits.maxBodyBytes()); // Always fits: reserved in tailBytes.
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

  private static String linkLabel(ChangeReport report) {
    return report.dryRun() ? "View full preview in state-db →" : "View full run in state-db →";
  }

  private static String stackPath(ChangeReport report) {
    ChangeReport.Coordinates at = report.coordinates();
    return at.org() + "/" + at.project() + "/" + at.stack();
  }

  /** The counts, in one line, compact enough for a notification or a commit status. */
  private String summaryLine(ChangeReport report) {
    List<String> parts = new ArrayList<>();
    for (Bucket bucket : nonZero(report)) {
      parts.add(bucket.glyph() + report.totals().get(bucket));
    }
    return parts.isEmpty() ? "No changes" : String.join(" ", parts);
  }

  private static List<Bucket> nonZero(ChangeReport report) {
    return Arrays.stream(Bucket.values())
        .filter(bucket -> report.totals().getOrDefault(bucket, 0) > 0)
        .toList();
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

  private static String stateLine(ChangeReport report) {
    return switch (state(report).toLowerCase(Locale.ROOT)) {
      case "succeeded" -> "**Succeeded**";
      case "failed" -> "**Failed**";
      case "cancelled", "canceled" -> "**Cancelled**";
      case "in-progress", "running", "pending" -> "**In progress**";
      default -> "**Status:** " + Markdown.code(state(report));
    };
  }

  /** Null when there is nothing to count, so the body never shows an empty line. */
  private static String operationSummary(ChangeReport report) {
    List<String> parts = new ArrayList<>();
    for (Bucket bucket : nonZero(report)) {
      parts.add(Markdown.code(bucket.glyph() + report.totals().get(bucket)) + " " + bucket.label());
    }
    if (parts.isEmpty()) {
      return null;
    }
    String partial =
        report.totalsSource() == CountSource.ROWS ? " (counted from a partial run)" : "";
    return "**Resources:** " + String.join(" · ", parts) + partial;
  }

  /** The truncation note and the link, each after a separator, reserved out of the budget. */
  private static int tailBytes(String link) {
    int note = SEPARATOR_BYTES + byteLength(TRUNCATION_NOTE);
    return link == null ? note : note + SEPARATOR_BYTES + byteLength(link);
  }

  /** Returns true only when every resource row was appended. */
  private boolean appendResources(Body body, ChangeReport report, int budget) {
    List<ResourceChange> prioritized = new ArrayList<>(report.resources());
    prioritized.sort(Comparator.comparingInt(resource -> reviewPriority(resource.op())));
    Markdown.DiffBlock plan = new Markdown.DiffBlock();
    int shown = 0;
    for (ResourceChange resource : prioritized) {
      String row = row(resource);
      if (!body.fits(PLAN_HEADING_BYTES + plan.bytesWith(row), budget)) {
        break;
      }
      plan.add(row);
      shown++;
    }
    if (shown == 0 || !body.append(PLAN_HEADING + plan.render(), budget)) {
      return false;
    }
    return shown == prioritized.size();
  }

  private static int reviewPriority(Op op) {
    Bucket bucket = Bucket.of(op);
    return bucket == null ? Bucket.OTHER.reviewPriority() : bucket.reviewPriority();
  }

  private String row(ResourceChange resource) {
    StringBuilder properties = new StringBuilder();
    for (PropertyChange property : resource.properties()) {
      if (properties.length() > 0) {
        properties.append(", ");
      }
      properties.append(displayPath(property.path()));
      if (property.kind().forcesReplacement()) {
        properties.append(" (replaces)");
      }
      if (property.secret()) {
        properties.append(" (secret)");
      }
    }
    if (resource.propertiesTruncated()) {
      if (properties.length() > 0) {
        properties.append(", ");
      }
      properties.append(ReportText.TRUNCATION_MARKER);
    }
    String details = properties.length() == 0 ? "" : "  [" + properties + "]";
    String line =
        marker(resource.op())
            + "  "
            + Markdown.diffToken(resource.type())
            + " "
            + Markdown.diffToken(resource.name())
            + details;
    return switch (resource.op()) {
      // + and - already carry both Pulumi meaning and diff highlighting.
      case CREATE, DELETE -> line;
      // GitHub's diff grammar gives changed lines and range lines distinct colors.
      case UPDATE -> "! " + line;
      case REPLACE -> "@@ " + line + " @@";
      default -> line;
    };
  }

  private static String displayPath(String path) {
    PropertyPath parsed = PropertyPath.parse(path);
    return parsed == null ? Markdown.diffToken(path) : parsed.display();
  }

  private static String marker(Op op) {
    return switch (op) {
      case CREATE -> "+";
      case UPDATE -> "~";
      case DELETE -> "-";
      case REPLACE -> "±";
      case REFRESH -> "r";
      case IMPORT -> "i";
      default -> "?";
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

  /** Accumulates body sections, tracking bytes and block separation. */
  private static final class Body {

    private final StringBuilder text = new StringBuilder();
    private int bytes;

    boolean append(String section, int budget) {
      int sectionBytes = byteLength(section);
      if (!fits(sectionBytes, budget)) {
        return false;
      }
      if (!text.isEmpty()) {
        text.append(SEPARATOR);
        bytes += SEPARATOR_BYTES;
      }
      text.append(section);
      bytes += sectionBytes;
      return true;
    }

    boolean fits(int sectionBytes, int budget) {
      return bytes + (text.isEmpty() ? 0 : SEPARATOR_BYTES) + sectionBytes <= budget;
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }
}
