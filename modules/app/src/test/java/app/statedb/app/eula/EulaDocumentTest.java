package app.statedb.app.eula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.eula.EulaDocument;
import app.statedb.testsupport.RepoRoot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The agreement is legal evidence, so the copy the product serves must be the copy the repository
 * publishes. These tests fail on any drift between them.
 */
@QuarkusTest
class EulaDocumentTest {

  @Inject EulaDocument document;

  @Test
  void bundledResourceMatchesTheRepositoryCopy() throws IOException {
    Path published = RepoRoot.get().resolve("EULA.md");
    assertEquals(
        Files.readString(published),
        document.text(),
        "EULA.md and modules/persistence/src/main/resources/legal/eula-"
            + EulaDocument.CURRENT_VERSION
            + ".md have drifted; copy the repository file over the bundled resource");
  }

  @Test
  void documentDeclaresItsOwnVersion() {
    assertEquals(EulaDocument.CURRENT_VERSION, document.version());
    assertTrue(
        document.text().contains("Version " + EulaDocument.CURRENT_VERSION),
        "the agreement text must name the version the code records");
  }

  @Test
  void digestIsLowercaseHexSha256() {
    assertTrue(
        document.sha256().matches("^[0-9a-f]{64}$"),
        "acceptance records constrain document_sha256 to lowercase hex: " + document.sha256());
  }

  @Test
  void testProfileAcceptsExactlyTheShippedVersion() throws IOException {
    String config =
        Files.readString(RepoRoot.get().resolve("modules/app/src/main/resources/application.yml"));
    assertTrue(
        config.contains("accepted-version: \"" + EulaDocument.CURRENT_VERSION + "\""),
        "the %test profile must accept the shipped version, or every stack-creating test fails");
  }
}
