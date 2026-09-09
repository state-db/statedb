package app.statedb.app.testsupport;

import com.cedarpolicy.value.EntityTypeName;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Loads cedar-java's JNI library before Quarkus and Testcontainers load their native libraries.
 *
 * <p>The Cedar Linux library uses static TLS. On some JVM/container combinations, loading it late
 * in the full app test suite fails with "cannot allocate memory in static TLS block" after other
 * native libraries have already consumed the available slots. Touching Cedar's value type
 * initializer when the JUnit launcher opens keeps the ordinary test order from deciding whether
 * Cedar can load.
 */
public final class CedarNativeLibraryPreloader implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    try {
      Class.forName(EntityTypeName.class.getName(), true, EntityTypeName.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "Cedar value type class is missing from the app test classpath", e);
    }
  }
}
