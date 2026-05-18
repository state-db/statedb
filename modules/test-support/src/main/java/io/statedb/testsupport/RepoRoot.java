package io.statedb.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the repository root by walking upward from the JVM's working directory until a directory
 * containing {@code .git} is found. Tests use this to reach checked-in fixtures (e.g. the recorder
 * project under {@code tools/pulumi/recorder/}) regardless of which Maven module ran them.
 */
public final class RepoRoot {

  private RepoRoot() {}

  public static Path get() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = current; p != null; p = p.getParent()) {
      if (Files.isDirectory(p.resolve(".git"))) {
        return p;
      }
    }
    throw new IllegalStateException("Could not locate repository root from " + current);
  }
}
