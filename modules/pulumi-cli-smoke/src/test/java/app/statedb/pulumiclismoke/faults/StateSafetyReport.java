package app.statedb.pulumiclismoke.faults;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Collects {@link FaultScenario} outcomes and writes {@code target/state-safety-report.md} — the
 * user-facing evidence that state-db survives the documented failure modes. The report is rewritten
 * after every scenario so a crashed run still leaves the results collected so far.
 */
public class StateSafetyReport implements TestWatcher, BeforeTestExecutionCallback {

  private record Entry(String scenario, String test, String result, Duration duration) {}

  private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
  private static final ConcurrentHashMap<String, Instant> STARTED = new ConcurrentHashMap<>();
  private static final Path REPORT = Path.of("target", "state-safety-report.md");

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    STARTED.put(context.getUniqueId(), Instant.now());
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    record(context, "PASS");
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    record(context, "FAIL");
  }

  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    record(context, "ABORTED");
  }

  private void record(ExtensionContext context, String result) {
    Optional<FaultScenario> scenario =
        context.getTestMethod().map(m -> m.getAnnotation(FaultScenario.class));
    if (scenario.isEmpty()) {
      return;
    }
    Instant started = STARTED.remove(context.getUniqueId());
    Duration duration = started == null ? Duration.ZERO : Duration.between(started, Instant.now());
    ENTRIES.add(
        new Entry(
            scenario.get().value(),
            context.getRequiredTestClass().getSimpleName()
                + "#"
                + context.getRequiredTestMethod().getName(),
            result,
            duration));
    write();
  }

  private static synchronized void write() {
    StringBuilder md = new StringBuilder();
    md.append("# State Safety Report\n\n");
    md.append("Fault-injection scenarios run against state-db with the real Pulumi CLI.\n\n");
    md.append("- Generated: ")
        .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC)))
        .append('\n');
    md.append("- Pulumi CLI image: `")
        .append(System.getProperty("pulumi.cli.image", "pulumi/pulumi:3.234.0"))
        .append("`\n\n");
    md.append("| Scenario | Test | Result | Duration |\n");
    md.append("|---|---|---|---|\n");
    for (Entry entry : ENTRIES) {
      md.append("| ")
          .append(entry.scenario())
          .append(" | `")
          .append(entry.test())
          .append("` | ")
          .append(entry.result())
          .append(" | ")
          .append(entry.duration().toSeconds())
          .append("s |\n");
    }
    try {
      Files.createDirectories(REPORT.getParent());
      Files.writeString(REPORT, md.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + REPORT, e);
    }
  }
}
