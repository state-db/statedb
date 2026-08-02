package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.consoleapi.auth.LocalGlass;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

/**
 * The loopback origin check that a {@code @QuarkusTest} cannot exercise negatively (RestAssured
 * always connects over loopback): non-loopback sources — including the Docker bridge gateway that
 * published ports arrive from — are rejected.
 */
class LocalGlassLoopbackTest {

  @Test
  void loopbackAddressesPass() {
    assertTrue(LocalGlass.isLoopback(SocketAddress.inetSocketAddress(8080, "127.0.0.1")));
    assertTrue(LocalGlass.isLoopback(SocketAddress.inetSocketAddress(8080, "::1")));
  }

  @Test
  void remoteAddressesFail() {
    assertFalse(LocalGlass.isLoopback(SocketAddress.inetSocketAddress(8080, "10.0.0.5")));
    assertFalse(LocalGlass.isLoopback(SocketAddress.inetSocketAddress(8080, "172.17.0.1")));
    assertFalse(LocalGlass.isLoopback(SocketAddress.inetSocketAddress(8080, "192.168.1.10")));
  }

  @Test
  void missingAddressFails() {
    assertFalse(LocalGlass.isLoopback(null));
    assertFalse(LocalGlass.isLoopback(SocketAddress.domainSocketAddress("/tmp/sock")));
  }
}
