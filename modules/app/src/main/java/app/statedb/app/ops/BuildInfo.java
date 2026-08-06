package app.statedb.app.ops;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build identity, filtered into {@code build-info.properties} at package time from properties the
 * image build supplies. An unstamped build reports {@code unknown} rather than a plausible default.
 */
public record BuildInfo(String commit, String builtAt, String build) {

  private static final String RESOURCE = "build-info.properties";
  private static final String UNKNOWN = "unknown";

  static BuildInfo fromClasspath() {
    var properties = new Properties();
    try (InputStream in = BuildInfo.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in != null) {
        properties.load(in);
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + RESOURCE, e);
    }
    return new BuildInfo(
        properties.getProperty("build.commit", UNKNOWN),
        properties.getProperty("build.timestamp", UNKNOWN),
        properties.getProperty("build.version", UNKNOWN));
  }
}
