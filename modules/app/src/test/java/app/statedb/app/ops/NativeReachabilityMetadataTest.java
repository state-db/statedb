package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.policy.ClasspathPolicyStore;
import app.statedb.testsupport.RepoRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterResources;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Validates the version-pinned dependency/private metadata that cannot live beside its source. */
class NativeReachabilityMetadataTest {

  /** Each central metadata file, keyed by the Maven property its pinned directory must track. */
  private static final Map<String, String> METADATA_BY_VERSION_PROPERTY =
      Map.of(
          "cedar-java.version",
              "/META-INF/native-image/app.statedb/state-db-cedar-4.10.0/reachability-metadata.json",
          "zstd-jni.version",
              "/META-INF/native-image/app.statedb/state-db-zstd-1.5.7-6/reachability-metadata.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void everyCentralTypeMemberAndResourceStillExists() throws Exception {
    List<String> violations = new ArrayList<>();
    for (String location : METADATA_BY_VERSION_PROPERTY.values()) {
      JsonNode metadata;
      try (InputStream input = getClass().getResourceAsStream(location)) {
        assertNotNull(input, "native reachability metadata was not packaged at " + location);
        metadata = MAPPER.readTree(input);
      }
      violations.addAll(
          NativeMetadataValidator.validate(location, metadata, productionResources()));
    }
    assertEquals(List.of(), violations, () -> String.join("\n", violations));
  }

  @Test
  void everyMetadataPathTracksItsPinnedDependencyVersion() throws IOException {
    String parentPom = Files.readString(RepoRoot.get().resolve("modules/pom.xml"));

    METADATA_BY_VERSION_PROPERTY.forEach(
        (property, location) -> {
          var matcher =
              Pattern.compile("<" + property + ">([^<]+)</" + property + ">").matcher(parentPom);
          assertTrue(matcher.find(), "modules/pom.xml has no " + property + " property");
          String version = matcher.group(1);

          assertTrue(
              location.contains("-" + version + "/"),
              () ->
                  "metadata path must include pinned "
                      + property
                      + " "
                      + version
                      + ": "
                      + location);
        });
  }

  @Test
  void sourceLocalResourceGlobsMatchProductionResources() {
    RegisterResources registration =
        ClasspathPolicyStore.class.getAnnotation(RegisterResources.class);
    assertNotNull(
        registration,
        "ClasspathPolicyStore computes resource names and must declare @RegisterResources");
    var metadata = MAPPER.createObjectNode();
    var resources = metadata.putArray("resources");
    for (String glob : registration.globs()) {
      resources.addObject().put("glob", glob);
    }

    List<String> violations =
        NativeMetadataValidator.validate(
            "ClasspathPolicyStore.java @RegisterResources", metadata, productionResources());
    assertEquals(List.of(), violations, () -> String.join("\n", violations));
  }

  private static Set<String> productionResources() {
    Path modules = RepoRoot.get().resolve("modules");
    Set<String> resources = new TreeSet<>();
    try (var modulePaths = Files.list(modules)) {
      modulePaths
          .map(path -> path.resolve("src/main/resources"))
          .filter(Files::isDirectory)
          .forEach(root -> addResources(root, resources));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
    return Set.copyOf(resources);
  }

  private static void addResources(Path root, Set<String> resources) {
    try (var paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .map(root::relativize)
          .map(Path::toString)
          .forEach(resources::add);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
