package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.testsupport.RepoRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code bin/check-native-metadata-drift} over temporary files. The script decides what the
 * tracing agent's raw observations mean: which of them state-db owns, and whether the reviewed
 * baseline still describes them.
 */
class NativeMetadataDriftCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * {@link #cedarType} for {@code ExistingRequest} after the script canonicalizes it: jq's
   * pretty-printed form with object keys sorted.
   */
  private static final String BASELINE =
      """
      {
        "reflection": [
          {
            "methods": [
              {
                "name": "<init>",
                "parameterTypes": [
                  "java.lang.String"
                ]
              }
            ],
            "type": "com.cedarpolicy.model.ExistingRequest"
          }
        ]
      }
      """;

  @TempDir Path workspace;

  /**
   * The reviewed baseline is the script's own canonical output, so every other test's expectation
   * is anchored on this one still passing.
   */
  @Test
  void anObservedCedarTypeAlreadyInTheBaselineIsNotDrift() throws Exception {
    Drift drift = check(observed(cedarType("com.cedarpolicy.model.ExistingRequest")), BASELINE);

    assertEquals(0, drift.exitCode(), drift.output());
  }

  @Test
  void aNewlyObservedCedarTypeFailsWithAnActionableDiagnostic() throws Exception {
    Drift drift =
        check(
            observed(
                cedarType("com.cedarpolicy.model.ExistingRequest"),
                cedarType("com.cedarpolicy.serializer.NewSerializer")),
            BASELINE);

    assertEquals(1, drift.exitCode(), drift.output());
    assertTrue(drift.output().contains("Native contract violation"), drift.output());
    assertTrue(drift.output().contains("com.cedarpolicy.serializer.NewSerializer"), drift.output());
    assertTrue(
        Files.isRegularFile(drift.normalizedPath()),
        "normalized metadata should remain for review");
  }

  /** A narrowed traced workload must fail loudly rather than quietly shrinking Cedar coverage. */
  @Test
  void aCedarTypeThatIsNoLongerObservedFailsAsDrift() throws Exception {
    Drift drift = check(observed(), BASELINE);

    assertEquals(1, drift.exitCode(), drift.output());
    assertTrue(drift.output().contains("com.cedarpolicy.model.ExistingRequest"), drift.output());
  }

  /** Quarkus registers application types from the Jandex index, so they are not tracked here. */
  @Test
  void anObservedApplicationTypeIsNotDrift() throws Exception {
    Drift drift = check(observed(cedarType("app.statedb.persistence.stack.Stack")), "{}\n");

    assertEquals(0, drift.exitCode(), drift.output());
  }

  /**
   * Parameter lists are signatures, not sets: order carries meaning and a repeated type is a second
   * parameter. Canonicalization must leave them alone or it rewrites the observation into a method
   * that does not exist, and a real signature change compares equal.
   */
  @Test
  void methodSignaturesAreNeitherSortedNorDeduplicated() throws Exception {
    Drift drift =
        check(
            """
            {
              "reflection": [
                {
                  "type": "com.cedarpolicy.model.policy.Policy",
                  "methods": [
                    {"name": "<init>", "parameterTypes": ["java.lang.String", "java.lang.String"]},
                    {"name": "of", "parameterTypes": ["java.util.List", "java.lang.String"]}
                  ]
                }
              ]
            }
            """,
            "{}\n");

    JsonNode methods = drift.normalized().at("/reflection/0/methods");
    assertEquals(
        List.of(
            List.of("java.lang.String", "java.lang.String"),
            List.of("java.util.List", "java.lang.String")),
        methods.findValues("parameterTypes").stream()
            .map(types -> types.valueStream().map(JsonNode::asText).toList())
            .toList(),
        () -> "parameter lists must survive verbatim: " + drift.normalized().toPrettyString());
  }

  /** One reflection entry for a Cedar type, in the shape the tracing agent emits. */
  private static String cedarType(String type) {
    return """
        {
          "type": "%s",
          "methods": [{"name": "<init>", "parameterTypes": ["java.lang.String"]}]
        }
        """
        .formatted(type);
  }

  private static String observed(String... reflectionEntries) {
    return "{\"reflection\": [%s]}".formatted(String.join(",", reflectionEntries));
  }

  private Drift check(String observed, String reviewed) throws Exception {
    Path generated = Files.createDirectories(workspace.resolve("generated/fork-1"));
    Path reviewedPath = workspace.resolve("reviewed/reachability-metadata.json");
    Path normalizedPath = workspace.resolve("normalized/reachability-metadata.json");
    Path productionResources = workspace.resolve("production-resources.txt");

    write(generated.resolve("reachability-metadata.json"), observed);
    write(reviewedPath, reviewed);
    write(productionResources, "");

    Process process =
        new ProcessBuilder(
                RepoRoot.get().resolve("bin/check-native-metadata-drift").toString(),
                "--generated",
                generated.toString(),
                "--reviewed",
                reviewedPath.toString(),
                "--normalized",
                normalizedPath.toString(),
                "--production-resources",
                productionResources.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    return new Drift(process.waitFor(), output, normalizedPath);
  }

  private static void write(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  private record Drift(int exitCode, String output, Path normalizedPath) {

    JsonNode normalized() {
      try {
        return MAPPER.readTree(normalizedPath.toFile());
      } catch (IOException error) {
        throw new IllegalStateException("no normalized metadata at " + normalizedPath, error);
      }
    }
  }
}
