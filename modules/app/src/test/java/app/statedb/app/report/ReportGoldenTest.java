package app.statedb.app.report;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.events.EventArchiveFolder;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.events.UpdateEventArchive;
import app.statedb.persistence.events.UpdateEventReader;
import app.statedb.persistence.events.UpdateEventView;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.report.ChangeReport;
import app.statedb.persistence.report.ChangeReportDeriver;
import app.statedb.persistence.report.MarkdownReportRenderer;
import app.statedb.persistence.report.RenderedReport;
import app.statedb.persistence.report.ReportLimits;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.Workspace;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The rendered report, pinned against a golden file, over a realistic mixed run.
 *
 * <p><b>The same run is asserted twice: once from live {@code update_events} rows, and once after
 * {@link EventArchiveFolder} has folded it.</b> That is the whole point of the test. Folding leaves
 * the {@code summary} rows behind and deletes the rest, so a report built from a direct query would
 * not fail — it would quietly become a summary-only report for every run older than the fold grace
 * period. Only a test that folds and re-derives can tell the two apart, and it can only tell them
 * apart because the reader reports which source answered.
 *
 * <p>Regenerate the golden file with {@code -Dstatedb.report.golden.update=true} and read the diff.
 */
@QuarkusTest
class ReportGoldenTest {

  private static final String GOLDEN = "modules/app/src/test/resources/report/report-mixed-run.md";
  private static final String CORPUS = "/report/events-mixed-run.json";
  private static final Instant STARTED = Instant.parse("2026-09-18T10:00:00Z");
  private static final Instant ENDED = Instant.parse("2026-09-18T10:02:30Z");
  private static final URI CONSOLE =
      URI.create("https://statedb.example.invalid/shop-org/shop/dev/previews/run");

  @Inject ChangeReportDeriver deriver;
  // The application's own mapper, so the serialized shape asserted here is the shape the
  // endpoint actually returns rather than a default mapper's approximation of it.
  @Inject ObjectMapper mapper;
  @Inject MarkdownReportRenderer renderer;
  @Inject UpdateEventReader reader;
  @Inject EventArchiveFolder folder;

  private String org;

  @AfterEach
  void cleanup() {
    if (org == null) {
      return;
    }
    String name = org;
    org = null;
    tx(() -> Organization.delete("name", name));
  }

  @Test
  void rendersTheSameReportFromLiveRowsAndFromAFoldedArchive() throws Exception {
    Seeded seeded = seed();

    UpdateEventReader.EventStream live =
        inTx(() -> reader.stream(seeded.stackId(), seeded.updateId(), 10_000));
    assertFalse(live.archived(), "precondition: this run is not folded yet");
    assertFalse(live.truncated());
    RenderedReport fromRows = render(seeded, live.events());

    assertEquals(golden(fromRows.body()), fromRows.body());
    assertFalse(fromRows.truncated());

    foldRun(seeded.updateId());
    assertNotNull(
        inTx(() -> UpdateEventArchive.<UpdateEventArchive>findById(seeded.updateId())),
        "precondition: the run was folded");
    // The rows the fold left behind: a query-based derivation would see only these.
    assertTrue(
        inTx(
                () ->
                    UpdateEvent.count(
                        "updateId = ?1 and kind = ?2", seeded.updateId(), UpdateEvent.SUMMARY_KIND))
            > 0,
        "precondition: summary rows survive the fold");

    UpdateEventReader.EventStream archived =
        inTx(() -> reader.stream(seeded.stackId(), seeded.updateId(), 10_000));
    assertTrue(archived.archived(), "the fold must be the source now");
    assertEquals(live.events(), archived.events(), "the archive must round-trip every event");

    RenderedReport fromArchive = render(seeded, archived.events());
    assertEquals(fromRows.body(), fromArchive.body(), "a folded run must report identically");
    assertEquals(fromRows.title(), fromArchive.title());
    assertEquals(fromRows.summaryLine(), fromArchive.summaryLine());
  }

  @Test
  void reportsWhatTheRunActuallyDid() throws Exception {
    Seeded seeded = seed();
    ChangeReport report =
        deriver.derive(
            seeded.ref(),
            inTx(() -> StackUpdate.<StackUpdate>findById(seeded.updateId())),
            inTx(() -> reader.stream(seeded.stackId(), seeded.updateId(), 10_000)).events(),
            ReportLimits.defaults());

    // `same` is not a change and must not occupy a row; a reader is looking for what moved.
    assertEquals(
        List.of("assets", "db", "cache", "legacy", "jobs"),
        report.resources().stream().map(ChangeReport.ResourceChange::name).toList());

    // A replacement arrives as create-replacement and delete-replaced against one URN. Two rows
    // for one resource, one saying create and one saying delete, would misread as two changes.
    ChangeReport.ResourceChange jobs = resource(report, "jobs");
    assertEquals(ChangeReport.Op.REPLACE, jobs.op());

    // A resOutputs visit carries no diff. The pre-event's property list must survive it.
    ChangeReport.ResourceChange db = resource(report, "db");
    assertEquals(
        List.of("instanceClass", "password", "tags.env"),
        db.properties().stream().map(ChangeReport.PropertyChange::path).toList());

    // The secret is structural: old.outputs.password carries the wrapper. `password` is the path
    // the provider named, and `inputDiff` is absent there, so the old *outputs* bag is the one to
    // read — which is where the wrapper is.
    assertTrue(property(db, "password").secret(), "a wrapped secret must be marked");
    assertFalse(property(db, "tags.env").secret());
    assertEquals(ChangeReport.DiffKind.UPDATE_REPLACE, property(db, "instanceClass").kind());
    assertTrue(property(db, "instanceClass").kind().forcesReplacement());

    // A coarse `diffs` list names top-level changed inputs and carries no kind of its own.
    ChangeReport.ResourceChange cache = resource(report, "cache");
    assertEquals(List.of("size"), cache.properties().stream().map(p -> p.path()).toList());
    assertEquals(ChangeReport.DiffKind.UNKNOWN, property(cache, "size").kind());

    // Counts come from the engine's summary, not from the rows above.
    assertEquals(1, report.counts().get(ChangeReport.Op.CREATE));
    assertEquals(2, report.counts().get(ChangeReport.Op.UPDATE));
    assertEquals(1, report.counts().get(ChangeReport.Op.SAME));
    // Replacement substeps fold into one replace; the source says these are the engine's totals.
    assertEquals(1, report.totals().get(ChangeReport.Bucket.REPLACE));
    assertEquals(ChangeReport.CountSource.ENGINE, report.totalsSource());
    assertEquals("succeeded", report.result());
    assertEquals(1, report.diagnostics().get(ChangeReport.DiagnosticSeverity.WARNING));
    assertFalse(report.truncated());
  }

  /** No values on the surface at all — the default the whole contract rests on. */
  @Test
  void carriesNoPropertyValuesAnywhere() throws Exception {
    Seeded seeded = seed();
    ChangeReport report =
        deriver.derive(
            seeded.ref(),
            inTx(() -> StackUpdate.<StackUpdate>findById(seeded.updateId())),
            inTx(() -> reader.stream(seeded.stackId(), seeded.updateId(), 10_000)).events(),
            ReportLimits.defaults());
    String serialized = mapper.writeValueAsString(report);
    String body = renderer.render(report, CONSOLE, ReportLimits.defaults()).body();

    // Values present in the corpus, on both sides of the diff, secret and not.
    for (String value :
        List.of("db.t3.micro", "db.t3.small", "staging", "production", "ciphertext", "opaque")) {
      assertFalse(serialized.contains(value), "value leaked into the JSON report: " + value);
      assertFalse(body.contains(value), "value leaked into the body: " + value);
    }
  }

  private RenderedReport render(Seeded seeded, List<app.statedb.persistence.events.StoredEvent> e) {
    StackUpdate update = inTx(() -> StackUpdate.<StackUpdate>findById(seeded.updateId()));
    ChangeReport report = deriver.derive(seeded.ref(), update, e, ReportLimits.defaults());
    return renderer.render(report, CONSOLE, ReportLimits.defaults());
  }

  private static ChangeReport.ResourceChange resource(ChangeReport report, String name) {
    return report.resources().stream()
        .filter(r -> r.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no resource named " + name));
  }

  private static ChangeReport.PropertyChange property(
      ChangeReport.ResourceChange resource, String path) {
    return resource.properties().stream()
        .filter(p -> p.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no property at " + path));
  }

  /**
   * The golden body, or {@code actual} after rewriting the file when the update flag is set.
   *
   * <p>Returning {@code actual} on a rewrite rather than skipping keeps the run green only because
   * the file now matches, which is the honest outcome: the assertion has moved into the diff the
   * author has to read.
   */
  private String golden(String actual) throws Exception {
    Path path = app.statedb.testsupport.RepoRoot.get().resolve(GOLDEN);
    if (Boolean.getBoolean("statedb.report.golden.update")) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, actual + "\n", StandardCharsets.UTF_8);
      return actual;
    }
    return Files.readString(path, StandardCharsets.UTF_8).stripTrailing();
  }

  private Seeded seed() throws Exception {
    this.org = "report-golden-" + System.nanoTime();
    String orgName = this.org;
    List<JsonNode> corpus = corpus();
    return read(
        () -> {
          Organization organization = new Organization();
          organization.name = orgName;
          organization.displayName = orgName;
          organization.persist();
          Project project = new Project();
          project.orgId = organization.id;
          project.name = "shop";
          project.persist();
          Workspace workspace = new Workspace();
          workspace.orgId = organization.id;
          workspace.kind = Workspace.Kind.pulumi;
          workspace.persist();
          Stack stack = new Stack();
          stack.orgId = organization.id;
          stack.projectId = project.id;
          stack.workspaceId = workspace.id;
          stack.name = "dev";
          stack.persistAndFlush();

          StackUpdate update = new StackUpdate();
          update.stackId = stack.id;
          update.kind = StackUpdate.Kind.PREVIEW;
          update.status = StackUpdate.Status.SUCCEEDED;
          update.dryRun = true;
          update.version = 0;
          update.startedAt = STARTED;
          // Well outside the fold grace period, so foldOnce() would take it — this test folds
          // explicitly, but an eligible update is the realistic case.
          update.endedAt = ENDED;
          update.persistAndFlush();

          for (JsonNode raw : corpus) {
            UpdateEventView view = new UpdateEventView(raw);
            UpdateEvent event = new UpdateEvent();
            event.stackId = stack.id;
            event.updateId = update.id;
            event.sequence = view.sequence();
            event.kind = view.kind();
            event.severity = view.severity();
            event.payload = raw;
            event.persist();
          }

          StackRef ref =
              new StackRef(
                  stack.id,
                  stack.workspaceId,
                  stack.orgId,
                  "shop-org",
                  "shop",
                  "dev",
                  0,
                  null,
                  stack.visibility);
          return new Seeded(stack.id, update.id, ref);
        });
  }

  private static List<JsonNode> corpus() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in = ReportGoldenTest.class.getResourceAsStream(CORPUS)) {
      assertNotNull(in, "missing corpus " + CORPUS);
      List<JsonNode> events = new ArrayList<>();
      mapper.readTree(in).forEach(events::add);
      return events;
    }
  }

  private <T> T inTx(Supplier<T> work) {
    return read(work::get);
  }

  /** The stack coordinates are fixed rather than taken from the seeded row so the golden is. */
  private record Seeded(UUID stackId, UUID updateId, StackRef ref) {}

  private void foldRun(UUID updateId) {
    tx(() -> folder.fold(updateId, Instant.now()));
  }
}
