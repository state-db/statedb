package app.statedb.app.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Pins the probe and scrape endpoints a deployment depends on, and the gauges they expose. */
@QuarkusTest
class ManagementEndpointsTest {

  // Relative to the management root, which already contributes the /q prefix.
  @TestHTTPResource(value = "/health/live", management = true)
  URL liveness;

  @TestHTTPResource(value = "/health/ready", management = true)
  URL readiness;

  @TestHTTPResource(value = "/metrics", management = true)
  URL metrics;

  @Inject DataSource dataSource;

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void probesReportUpAndReadinessChecksTheDatabase() throws Exception {
    assertEquals(200, get(liveness).statusCode());

    HttpResponse<String> response = get(readiness);
    assertEquals(200, response.statusCode());

    JsonNode body = mapper.readTree(response.body());
    assertEquals("UP", body.path("status").asText(), response.body());
    assertTrue(
        body.path("checks").toString().toLowerCase(Locale.ROOT).contains("database"),
        "readiness should include a datasource check: " + response.body());
  }

  @Test
  void metricsExposeJvmAndJdbcPoolGauges() throws Exception {
    // Datasources initialize lazily; borrowing a connection builds the pool before the scrape.
    touchPool();

    HttpResponse<String> response = get(metrics);
    assertEquals(200, response.statusCode());

    String body = response.body();
    assertTrue(body.contains("jvm_memory_used_bytes"), "missing JVM heap gauge");
    assertTrue(body.contains("jvm_gc_"), "missing GC metrics");
    assertTrue(body.contains("agroal_active_count"), "missing JDBC pool gauge");
    assertTrue(body.contains("agroal_awaiting_count"), "missing JDBC pool saturation gauge");
  }

  private void touchPool() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertTrue(connection.isValid(5));
    }
  }

  private HttpResponse<String> get(URL url) throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create(url.toString())).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
