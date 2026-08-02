package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Smoke coverage for {@code pulumi watch}. It drives the same update lifecycle as {@code up} but
 * never exits on its own, so this test runs it detached, waits for its first deploy to land, and
 * then kills it — the point is that a long-lived watcher's update completes and releases its lease
 * against our backend like any other update.
 */
@QuarkusTest
class WatchSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT = "watch-smoke";
  private static final String PET_TYPE = "random:index/randomPet:RandomPet";

  @Test
  void appliesTheFirstUpdateAndRecordsItInHistory() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(watchProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);

    execDetached("pulumi watch --non-interactive");
    awaitTrue(
        "watch to arm its file watcher",
        Duration.ofMinutes(1),
        () -> watchLog().contains("Watch mode enabled"));

    // watch deploys on a source change, not on startup — a comment appended to the program is
    // enough to trigger its first pass. History lists in-progress updates too, so wait for the
    // result to settle: "succeeded" is what proves the lifecycle ran through /complete.
    execResult("echo '# edit to trigger watch' >> Pulumi.yaml");
    try {
      awaitTrue(
          "watch to complete its first update",
          Duration.ofMinutes(3),
          () ->
              "succeeded"
                  .equals(execJson("pulumi stack history --json").path(0).path("result").asText()));
    } catch (AssertionError e) {
      // The detached process's output is the only evidence of why it never deployed.
      throw new AssertionError("watch log:\n" + watchLog(), e);
    }

    JsonNode history = execJson("pulumi stack history --json");
    assertEquals("update", history.get(0).path("kind").asText(), () -> "history:\n" + history);

    JsonNode exported = execJson("pulumi stack export");
    assertTrue(
        findResource(exported, PET_TYPE, "pet").isPresent(),
        () -> "watch's deploy should have created the pet:\n" + exported);

    pulumi.execInContainer("sh", "-lc", "pkill -f 'pulumi watch' || true");
  }

  private String watchLog() throws Exception {
    return execAllowingFailure("cat /tmp/detached.log").getStdout();
  }

  private static String watchProject() {
    return """
        name: %s
        runtime: yaml
        description: Smoke project for the watch command.
        resources:
          pet:
            type: random:RandomPet
            properties:
              length: 2
        """
        .formatted(PROJECT);
  }
}
