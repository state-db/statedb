package io.statedb.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the repository root by walking upward from the JVM's working directory. Tests use this
 * to reach checked-in fixtures (e.g. the recorder project under {@code tools/pulumi/recorder/})
 * regardless of which Maven module ran them.
 */
public final class RepoRoot {

  private RepoRoot() {}

  public static Path get() {
    return findFrom(Path.of(System.getProperty("user.dir")));
  }

  static Path findFrom(Path start) {
    Path current = start.toAbsolutePath().normalize();
    for (Path p = current; p != null; p = p.getParent()) {
      if (isRoot(p)) {
        return p;
      }
    }
    throw new IllegalStateException("Could not locate repository root from " + current);
  }

  private static boolean isRoot(Path path) {
    if (Files.exists(path.resolve(".git"))) {
      return true;
    }
    return Files.isRegularFile(path.resolve("modules/pom.xml"))
        && Files.isDirectory(path.resolve("tools/pulumi/verify"));
  }
}
