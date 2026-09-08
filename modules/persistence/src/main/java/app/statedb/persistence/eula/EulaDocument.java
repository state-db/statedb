package app.statedb.persistence.eula;

import io.quarkus.runtime.annotations.RegisterResources;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The End User License Agreement this image ships, read from the classpath.
 *
 * <p>The text is bundled rather than fetched so an air-gapped deployment can display the exact
 * agreement its administrator is accepting. {@link #sha256()} is recorded alongside every
 * acceptance: the version identifier says which agreement was accepted, and the digest proves the
 * bytes were the ones this image served.
 *
 * <p>The canonical copy is {@code EULA.md} at the repository root; {@code EulaDocumentTest} fails
 * if the bundled resource drifts from it.
 */
@ApplicationScoped
@RegisterResources(globs = "legal/**")
public class EulaDocument {

  /**
   * Bump this — and add the matching {@code legal/eula-<version>.md} resource — when the agreement
   * changes materially. A deployment's administrator must accept the new version before the
   * deployment will create stacks again.
   */
  public static final String CURRENT_VERSION = "1.0";

  private static final String RESOURCE = "legal/eula-" + CURRENT_VERSION + ".md";

  // Read on first use rather than in the constructor: a missing resource is a packaging
  // failure that should surface where it is used, not leave a half-built CDI bean behind.
  private volatile String text;
  private volatile String sha256;

  public String version() {
    return CURRENT_VERSION;
  }

  /** The full Markdown agreement, for display in the console. */
  public String text() {
    String loaded = text;
    if (loaded == null) {
      synchronized (this) {
        loaded = text;
        if (loaded == null) {
          loaded = read(RESOURCE);
          sha256 = digest(loaded);
          text = loaded;
        }
      }
    }
    return loaded;
  }

  /** Lowercase hex SHA-256 of {@link #text()}. */
  public String sha256() {
    text();
    return sha256;
  }

  private static String read(String resource) {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing classpath resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("failed reading " + resource, e);
    }
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }
}
