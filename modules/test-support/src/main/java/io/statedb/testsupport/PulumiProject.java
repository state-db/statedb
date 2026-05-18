package io.statedb.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Parsed view of a Pulumi.yaml project file. The recorder project under {@code
 * tools/pulumi/recorder/testproject/} is the single source of truth for the project name, runtime,
 * and description used by both the contract tests (which seed matching stack tags) and the smoke IT
 * (which mounts the file into a real {@code pulumi} container).
 */
public record PulumiProject(String name, String runtime, String description) {

  public static final String RECORDER_PROJECT_PATH =
      "tools/pulumi/recorder/testproject/Pulumi.yaml";

  public static PulumiProject recorder() {
    return load(RepoRoot.get().resolve(RECORDER_PROJECT_PATH));
  }

  public static PulumiProject load(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      Object loaded = new Yaml(new LoaderOptions()).load(in);
      if (!(loaded instanceof Map<?, ?> config)) {
        throw new IllegalStateException(file + " is not a YAML map");
      }
      return new PulumiProject(
          requireString(config, "name", file),
          requireString(config, "runtime", file),
          requireString(config, "description", file));
    } catch (IOException e) {
      throw new IllegalStateException("Could not load " + file, e);
    }
  }

  private static String requireString(Map<?, ?> config, String key, Path file) {
    Object value = config.get(key);
    Objects.requireNonNull(value, () -> file + " is missing required key: " + key);
    return value.toString();
  }
}
