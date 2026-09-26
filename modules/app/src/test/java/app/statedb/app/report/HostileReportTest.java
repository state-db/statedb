package app.statedb.app.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.events.StoredEvent;
import app.statedb.persistence.events.UpdateEventView;
import app.statedb.persistence.report.ChangeReport;
import app.statedb.persistence.report.ChangeReportDeriver;
import app.statedb.persistence.report.MarkdownReportRenderer;
import app.statedb.persistence.report.RenderedReport;
import app.statedb.persistence.report.ReportLimits;
import app.statedb.persistence.report.RunChangeDeriver;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The derive-and-render pipeline against state that is trying to do something to the reader.
 *
 * <p>A resource name is an arbitrary string chosen by whoever wrote the Pulumi program, and the
 * report lands on a pull request whose audience is wider than the console's. Everything here is a
 * thing an author can actually put in a URN or a property path.
 *
 * <p>No container: derivation and rendering are pure, and the events are constructed rather than
 * persisted, so these cases cost nothing to keep adding to.
 */
class HostileReportTest {

  // findAndRegisterModules picks up jackson-datatype-jsr310, so the serialized shape asserted
  // here matches what the endpoint's configured mapper produces for the report's Instants.
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final URI CONSOLE = URI.create("https://statedb.example.invalid/o/p/s/previews/r");

  private final ChangeReportDeriver deriver = new ChangeReportDeriver(new RunChangeDeriver());
  private final MarkdownReportRenderer renderer = new MarkdownReportRenderer();

  @Test
  void aHostileUrnCannotEscapeIntoMarkdown() throws Exception {
    String body = render(corpus("/report/events-hostile-run.json"), ReportLimits.defaults()).body();
    String outside = outsideCodeLiterals(body);

    // Each of these is in the URN. None may be interpreted: a mention would notify, an issue
    // reference would cross-link, a link or an <img> would be live content on someone's PR.
    assertFalse(outside.contains("@everyone"), "mention escaped: " + outside);
    assertFalse(outside.contains("#1 "), "issue reference escaped: " + outside);
    assertFalse(outside.contains("evil.invalid"), "link target escaped: " + outside);
    assertFalse(outside.contains("<img"), "html escaped: " + outside);
    assertFalse(outside.contains("[click]"), "link syntax escaped: " + outside);

    // The hostile URL is still *shown* — a reader should see the URN as it really is — but only
    // as text. Exactly one URL is live, and it is ours.
    assertTrue(body.contains("evil.invalid"), "the URN should still be legible");
    assertEquals(1, countOccurrences(outside, "https://"), "exactly one live URL, ours");
    assertTrue(outside.contains(CONSOLE.toString()));
  }

  @Test
  void aPipeInAUrnOrPathStaysLiteralInThePlan() throws Exception {
    String body = render(corpus("/report/events-hostile-run.json"), ReportLimits.defaults()).body();
    assertFalse(body.contains("| Change |"), body);
    assertTrue(
        body.lines()
            .anyMatch(
                line ->
                    (line.startsWith("+")
                            || line.startsWith("! ~")
                            || line.startsWith("-")
                            || line.startsWith("@@ ±"))
                        && line.contains("|")),
        body);
  }

  @Test
  void anUnrecognisedSecretShapeIsStillMarkedSecret() throws Exception {
    // A future Pulumi signature we do not know. Pulumi's own unmarshaller refuses it outright, so
    // the only safe reading is that the value may be sensitive.
    ChangeReport report =
        derive(corpus("/report/events-hostile-run.json"), ReportLimits.defaults());
    assertTrue(property(report, "unknownSecretShape", "wrapped").secret());
  }

  @Test
  void anOutputValueFlaggedSecretIsMarkedSecretAndItsPlaintextNeverAppears() throws Exception {
    // The second secret shape. Its "value" is plaintext, so a redactor that only knew SecretSig
    // would both fail to mark it and — once values are ever rendered — emit it.
    List<StoredEvent> events = corpus("/report/events-hostile-run.json");
    ChangeReport report = derive(events, ReportLimits.defaults());
    assertTrue(property(report, "outputValueSecret", "token").secret());

    String body = renderer.render(report, CONSOLE, ReportLimits.defaults()).body();
    assertFalse(body.contains("plaintext-token-do-not-emit"));
    assertFalse(MAPPER.writeValueAsString(report).contains("plaintext-token-do-not-emit"));
  }

  @Test
  void pathsTheProviderHidDoNotAppear() throws Exception {
    // `hideDiffs` is Pulumi's own "do not show a diff here", and a report seen by more people than
    // the console has strictly more reason to honour it than the console does.
    ChangeReport report =
        derive(corpus("/report/events-hostile-run.json"), ReportLimits.defaults());
    List<String> paths =
        report.resources().stream()
            .filter(r -> r.name().equals("hiddenDiffs"))
            .flatMap(r -> r.properties().stream())
            .map(ChangeReport.PropertyChange::path)
            .toList();

    // The hidden path and everything under it, gone.
    assertFalse(paths.contains("credentials"));
    assertFalse(paths.contains("credentials.token"));
    // A name that merely starts with the hidden one is a different property and must survive.
    assertTrue(paths.contains("credentialsPublic"));
    assertTrue(paths.contains("shown"));

    assertFalse(
        renderer.render(report, CONSOLE, ReportLimits.defaults()).body().contains("credentials."));
  }

  @Test
  void internalPropertyPathsDoNotEnterThePullRequestReport() throws Exception {
    JsonNode metadata =
        MAPPER.readTree(
            """
            {"op":"update","urn":"urn:pulumi:dev::shop::pkg:index:Thing::bookkeeping",
             "type":"pkg:index:Thing",
             "old":{"inputs":{"shown":1,"__internal":"old"}},
             "new":{"inputs":{"shown":2,"__internal":"new"}},
             "detailedDiff":{
               "shown":{"diffKind":"update","inputDiff":true},
               "__internal":{"diffKind":"update","inputDiff":true}
             }}
            """);

    ChangeReport report = derive(List.of(step(0, metadata), summary(1)), ReportLimits.defaults());

    assertEquals(
        List.of("shown"),
        report.resources().getFirst().properties().stream()
            .map(ChangeReport.PropertyChange::path)
            .toList());
  }

  @Test
  void bidiAndInvisibleCodepointsInAUrnDoNotReachTheBody() throws Exception {
    // A right-to-left override would make the rendered name read as something other than the URN.
    String rtlOverride = new String(Character.toChars(0x202e));
    String zeroWidth = new String(Character.toChars(0x200b));
    List<StoredEvent> events =
        List.of(
            step(
                0,
                "urn:pulumi:dev::shop::pkg:index:Thing::safe"
                    + rtlOverride
                    + "dangerous"
                    + zeroWidth,
                "update"),
            summary(1));

    String body = render(events, ReportLimits.defaults()).body();
    assertFalse(body.contains(rtlOverride), "bidi override reached the body");
    assertFalse(body.contains(zeroWidth), "zero-width character reached the body");
    assertTrue(body.contains("safedangerous"));
  }

  @Test
  void namesAndKeysCannotImitateRowAnnotations() throws Exception {
    JsonNode metadata =
        MAPPER.readTree(
            """
            {"op":"update","urn":"urn:pulumi:dev::shop::pkg:index:Thing::logs  [acl (replaces)]",
             "type":"pkg:index:Thing",
             "old":{"inputs":{"size":1,"tags":{"a, password (secret)":"x"}}},
             "new":{"inputs":{"size":2,"tags":{"a, password (secret)":"y"}}},
             "detailedDiff":{
               "size":{"diffKind":"update-replace","inputDiff":true},
               "tags[\\"a, password (secret)\\"]":{"diffKind":"update","inputDiff":true}
             }}
            """);

    String body = render(List.of(step(0, metadata), summary(1)), ReportLimits.defaults()).body();

    // Anything that is not one plain word is quoted; only the renderer's own text sits outside.
    assertTrue(
        body.contains(
            "! ~  pkg:index:Thing \"logs [acl (replaces)]\""
                + "  [size (replaces), tags[\"a, password (secret)\"]]"),
        body);
  }

  @Test
  void aNewlineInAUrnCannotBreakOutOfItsRow() throws Exception {
    List<StoredEvent> events =
        List.of(
            step(0, "urn:pulumi:dev::shop::pkg:index:Thing::a\nb\n| evil | row |", "update"),
            summary(1));
    String body = render(events, ReportLimits.defaults()).body();
    long rows = body.lines().filter(line -> line.startsWith("! ~  ")).count();
    assertEquals(1, rows, "a newline forged extra rows:\n" + body);
  }

  @Test
  void aFortyResourceRunFitsAsCompactPlanLines() throws Exception {
    List<StoredEvent> events = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      events.add(step(i, "urn:pulumi:dev::shop::pkg:index:Thing::resource-" + i, "update"));
    }
    events.add(summary(40, 40));

    RenderedReport rendered = render(events, ReportLimits.defaults());
    String body = rendered.body();

    assertTrue(body.contains("**Resources:** `~40` update"), body);
    assertFalse(body.contains("<details>"), body);
    assertFalse(body.contains("🟡"), body);
    assertTrue(body.contains("```diff"), body);
    assertEquals(40, body.lines().filter(line -> line.startsWith("! ~  ")).count());
    assertTrue(body.indexOf("resource-39") < body.indexOf(CONSOLE.toString()), body);
    assertFalse(rendered.truncated(), body);
  }

  @Test
  void aBudgetCutKeepsHighRiskResourcesAndReportsTruncation() throws Exception {
    List<StoredEvent> events = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      events.add(step(i, "urn:pulumi:dev::shop::pkg:index:Thing::replacement-" + i, "replace"));
    }
    for (int i = 0; i < 6; i++) {
      events.add(step(5 + i, "urn:pulumi:dev::shop::pkg:index:Thing::creation-" + i, "create"));
    }
    for (int i = 0; i < 3; i++) {
      events.add(step(11 + i, "urn:pulumi:dev::shop::pkg:index:Thing::deletion-" + i, "delete"));
    }
    events.add(summary(14, 6, 0, 3, 5));

    ReportLimits limits = new ReportLimits(200, 25, 256, 32, 700);
    RenderedReport rendered = render(events, limits);
    String body = rendered.body();

    assertEquals(3, body.lines().filter(line -> line.startsWith("-  ")).count(), body);
    assertEquals(5, body.lines().filter(line -> line.startsWith("@@ ±  ")).count(), body);
    assertTrue(body.contains("deletion-0"), body);
    assertTrue(body.contains("deletion-1"), body);
    assertTrue(body.contains("deletion-2"), body);
    assertTrue(body.indexOf("deletion-0") < body.indexOf("replacement-0"), body);
    assertTrue(rendered.truncated(), "the byte budget dropped resource rows");
    assertTrue(body.contains("_Truncated:"), body);
    assertTrue(body.contains(CONSOLE.toString()), body);
  }

  @Test
  void anOversizedRunIsTruncatedRatherThanEmittedWhole() throws Exception {
    // 5,000 resources with long names. A comment host would reject the POST outright, which is
    // worse than a truncated comment: the reader gets nothing at all.
    List<StoredEvent> events = new ArrayList<>();
    for (int i = 0; i < 5_000; i++) {
      events.add(
          step(i, "urn:pulumi:dev::shop::pkg:index:Thing::" + "n".repeat(500) + i, "update"));
    }
    events.add(summary(5_000));

    ReportLimits limits = ReportLimits.defaults();
    RenderedReport rendered = render(events, limits);

    assertTrue(rendered.truncated(), "an oversized run must say so");
    assertTrue(
        rendered.body().getBytes(StandardCharsets.UTF_8).length <= limits.maxBodyBytes(),
        "body exceeded its budget: " + rendered.body().length());
    // Truncation must never cost the reader the way out.
    assertTrue(rendered.body().contains(CONSOLE.toString()), "the link survives truncation");
    // And it must be stable: the same run always produces the same comment, so a re-delivery is
    // not a spurious edit.
    assertEquals(rendered.body(), render(events, limits).body());
  }

  @Test
  void noBudgetProducesABodyOverItsLimit() throws Exception {
    // Every budget boundary, not just the defaults: sections, separators and the reserved tail
    // must add up to at most maxBodyBytes wherever the cut falls.
    List<StoredEvent> events = corpus("/report/events-hostile-run.json");
    for (int max = 1; max <= 3_000; max++) {
      ReportLimits limits = new ReportLimits(200, 25, 256, 32, max);
      String body = render(events, limits).body();
      assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= max, max + ":\n" + body);
    }
  }

  @Test
  void aPathologicalConsoleLinkCannotExceedTheBodyBudget() throws Exception {
    ReportLimits limits = new ReportLimits(200, 25, 256, 32, 256);
    ChangeReport report =
        derive(
            List.of(
                step(0, "urn:pulumi:dev::shop::pkg:index:Thing::changed", "update"), summary(1)),
            limits);
    URI pathological = URI.create("https://statedb.example.invalid/" + "x".repeat(1_000));

    RenderedReport rendered = renderer.render(report, pathological, limits);

    assertTrue(rendered.truncated());
    assertTrue(rendered.body().getBytes(StandardCharsets.UTF_8).length <= limits.maxBodyBytes());
  }

  @Test
  void aPlanHeadingIsNeverEmittedWithoutAResourceLine() throws Exception {
    ChangeReport report =
        derive(
            List.of(
                step(0, "urn:pulumi:dev::shop::pkg:index:Thing::changed", "update"), summary(1)),
            ReportLimits.defaults());

    for (int budget = 1; budget <= 1_000; budget++) {
      ReportLimits limits = new ReportLimits(200, 25, 256, 32, budget);
      String body = renderer.render(report, null, limits).body();
      assertFalse(
          body.contains("**Plan:**") && body.lines().noneMatch(line -> line.startsWith("! ~  ")),
          "orphan plan heading at byte budget " + budget + ":\n" + body);
    }
  }

  @Test
  void aSingleOversizedFieldIsBoundedAndMarksTheReport() throws Exception {
    List<StoredEvent> events =
        List.of(
            step(0, "urn:pulumi:dev::shop::pkg:index:Thing::" + "x".repeat(1_000_000), "update"),
            summary(1));
    ChangeReport report = derive(events, ReportLimits.defaults());
    assertTrue(report.truncated());
    assertTrue(
        report
                .resources()
                .getFirst()
                .urn()
                .codePointCount(0, report.resources().getFirst().urn().length())
            <= ReportLimits.defaults().maxFieldCodePoints());
  }

  @Test
  void aRunWithNoChangesRendersAsNoChangesRatherThanAnEmptyTable() throws Exception {
    List<StoredEvent> events =
        List.of(step(0, "urn:pulumi:dev::shop::pkg:index:Thing::untouched", "same"), summary(1));
    RenderedReport rendered = render(events, ReportLimits.defaults());
    assertTrue(rendered.body().contains("No resource changes."));
    assertFalse(rendered.body().contains("| --- |"));
    assertFalse(rendered.truncated());
  }

  @Test
  void aRunWithNoSummaryStillReports() throws Exception {
    // A cancelled run never emits a summary. The report must fall back to statedb's own status
    // rather than rendering nothing or claiming success.
    List<StoredEvent> events =
        List.of(step(0, "urn:pulumi:dev::shop::pkg:index:Thing::partial", "create"));
    ChangeReport report =
        deriver.derive(
            ref(), update(StackUpdate.Status.CANCELLED), events, ReportLimits.defaults());
    assertEquals("cancelled", report.status());
    assertEquals(null, report.result());
    RenderedReport rendered = renderer.render(report, CONSOLE, ReportLimits.defaults());
    assertTrue(rendered.body().contains("**Cancelled**"));
    // Without a summary both the one-line summary and the body count the rows, and say so.
    assertEquals(ChangeReport.CountSource.ROWS, report.totalsSource());
    assertEquals("+1", rendered.summaryLine());
    assertTrue(
        rendered.body().contains("**Resources:** `+1` create (counted from a partial run)"),
        rendered.body());
  }

  private RenderedReport render(List<StoredEvent> events, ReportLimits limits) {
    return renderer.render(derive(events, limits), CONSOLE, limits);
  }

  private ChangeReport derive(List<StoredEvent> events, ReportLimits limits) {
    return deriver.derive(ref(), update(StackUpdate.Status.SUCCEEDED), events, limits);
  }

  private static StackRef ref() {
    return new StackRef(
        UUID.nameUUIDFromBytes("stack".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("workspace".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("org".getBytes(StandardCharsets.UTF_8)),
        "o",
        "p",
        "s",
        0,
        null,
        "internal");
  }

  private static StackUpdate update(StackUpdate.Status status) {
    StackUpdate update = new StackUpdate();
    update.id = UUID.nameUUIDFromBytes("update".getBytes(StandardCharsets.UTF_8));
    update.stackId = ref().stackId();
    update.kind = StackUpdate.Kind.PREVIEW;
    update.status = status;
    update.dryRun = true;
    update.startedAt = Instant.parse("2026-09-18T10:00:00Z");
    return update;
  }

  private static StoredEvent step(int sequence, String urn, String op) {
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("op", op);
    metadata.put("urn", urn);
    metadata.put("type", "pkg:index:Thing");
    ObjectNode event = MAPPER.createObjectNode();
    event.put("sequence", sequence);
    event.putObject("resourcePreEvent").set("metadata", metadata);
    return view(event);
  }

  private static StoredEvent step(int sequence, JsonNode metadata) {
    ObjectNode event = MAPPER.createObjectNode();
    event.put("sequence", sequence);
    event.putObject("resourcePreEvent").set("metadata", metadata);
    return view(event);
  }

  private static StoredEvent summary(int sequence) {
    return summary(sequence, 1);
  }

  private static StoredEvent summary(int sequence, int updates) {
    return summary(sequence, 0, updates, 0, 0);
  }

  private static StoredEvent summary(
      int sequence, int creates, int updates, int deletes, int replaces) {
    ObjectNode summary = MAPPER.createObjectNode();
    summary.put("result", "succeeded");
    ObjectNode changes = summary.putObject("resourceChanges");
    changes.put("create", creates);
    changes.put("update", updates);
    changes.put("delete", deletes);
    changes.put("replace", replaces);
    ObjectNode event = MAPPER.createObjectNode();
    event.put("sequence", sequence);
    event.set("summaryEvent", summary);
    return view(event);
  }

  private static StoredEvent view(JsonNode raw) {
    UpdateEventView parsed = new UpdateEventView(raw);
    return new StoredEvent(parsed.sequence(), parsed.kind(), parsed.severity(), raw);
  }

  private static List<StoredEvent> corpus(String resource) throws Exception {
    try (InputStream in = HostileReportTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, "missing corpus " + resource);
      List<StoredEvent> events = new ArrayList<>();
      MAPPER.readTree(in).forEach(raw -> events.add(view(raw)));
      return events;
    }
  }

  private static ChangeReport.PropertyChange property(
      ChangeReport report, String resourceName, String path) {
    return report.resources().stream()
        .filter(r -> r.name().equals(resourceName))
        .flatMap(r -> r.properties().stream())
        .filter(p -> p.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + resourceName + "." + path + " in " + report));
  }

  private static long countOccurrences(String haystack, String needle) {
    long count = 0;
    int from = 0;
    while (true) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + needle.length();
    }
  }

  /** See the identically named helper in {@code SanitizationContractTest} for why. */
  private static String outsideCodeLiterals(String markdown) {
    StringBuilder withoutFencedCode = new StringBuilder();
    String closingFence = null;
    for (String line : markdown.lines().toList()) {
      if (closingFence == null && line.matches("`{3,}diff")) {
        closingFence = line.substring(0, line.length() - "diff".length());
      } else if (closingFence != null) {
        if (line.equals(closingFence)) {
          closingFence = null;
        }
      } else {
        withoutFencedCode.append(line).append('\n');
      }
    }
    return outsideCodeSpans(withoutFencedCode.toString());
  }

  /** See the identically named helper in {@code SanitizationContractTest} for why. */
  private static String outsideCodeSpans(String markdown) {
    StringBuilder outside = new StringBuilder();
    int i = 0;
    while (i < markdown.length()) {
      if (markdown.charAt(i) == '`') {
        int fenceEnd = i;
        while (fenceEnd < markdown.length() && markdown.charAt(fenceEnd) == '`') {
          fenceEnd++;
        }
        String fence = markdown.substring(i, fenceEnd);
        int close = markdown.indexOf(fence, fenceEnd);
        if (close < 0) {
          outside.append(markdown, fenceEnd, markdown.length());
          break;
        }
        i = close + fence.length();
      } else {
        outside.append(markdown.charAt(i));
        i++;
      }
    }
    return outside.toString();
  }
}
