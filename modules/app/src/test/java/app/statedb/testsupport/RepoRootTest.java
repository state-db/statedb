package app.statedb.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoRootTest {

  @TempDir Path tempDir;

  @Test
  void resolvesRootFromProjectSentinelsWithoutGitMetadata() throws IOException {
    Path root = tempDir.resolve("checkout");
    Files.createDirectories(root.resolve("modules/app"));
    Files.createDirectories(root.resolve("tools/pulumi/verify"));
    Files.writeString(root.resolve("modules/pom.xml"), "<project/>");

    assertEquals(root, RepoRoot.findFrom(root.resolve("modules/app")));
  }
}
