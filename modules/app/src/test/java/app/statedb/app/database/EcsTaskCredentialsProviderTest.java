package app.statedb.app.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class EcsTaskCredentialsProviderTest {

  private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void cachesCredentialsOnlyWhenTheyOutliveTheRdsToken() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    Instant expiration = NOW.plus(RdsIamAuthenticationTokenGenerator.TOKEN_LIFETIME).plusSeconds(1);
    HttpServer server =
        credentialsServer(requests, request -> credentialsJson(request, expiration));

    try {
      EcsTaskCredentialsProvider credentials = provider(server);

      assertEquals("container-access-key-1", credentials.resolveCredentials().accessKeyId());
      assertEquals("container-access-key-1", credentials.resolveCredentials().accessKeyId());
      assertEquals(1, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refreshesCredentialsAtTheRdsTokenLifetimeBoundary() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    Instant expiration = NOW.plus(RdsIamAuthenticationTokenGenerator.TOKEN_LIFETIME);
    HttpServer server =
        credentialsServer(requests, request -> credentialsJson(request, expiration));

    try {
      EcsTaskCredentialsProvider credentials = provider(server);

      assertEquals("container-access-key-1", credentials.resolveCredentials().accessKeyId());
      assertEquals("container-access-key-2", credentials.resolveCredentials().accessKeyId());
      assertEquals(2, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsNonSuccessfulMetadataResponse() throws Exception {
    HttpServer server = responseServer(503, request -> "unavailable");

    try {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> provider(server).resolveCredentials());

      assertEquals("ECS task credentials endpoint returned HTTP 503", failure.getMessage());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsMalformedMetadataResponse() throws Exception {
    HttpServer server = responseServer(200, request -> "{");

    try {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> provider(server).resolveCredentials());

      assertEquals("Could not read ECS task credentials", failure.getMessage());
      assertInstanceOf(JsonProcessingException.class, failure.getCause());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsMissingMetadataFields() throws Exception {
    assertMissingField("{}", "AccessKeyId");
    assertMissingField("{\"AccessKeyId\":\"key\"}", "SecretAccessKey");
    assertMissingField("{\"AccessKeyId\":\"key\",\"SecretAccessKey\":\"secret\"}", "Token");
    assertMissingField(
        "{\"AccessKeyId\":\"key\",\"SecretAccessKey\":\"secret\",\"Token\":\"token\"}",
        "Expiration");
  }

  @Test
  void rejectsMissingOrUnsafeRelativeCredentialPath() {
    assertInvalidPath(null);
    assertInvalidPath("");
    assertInvalidPath("credentials");
    assertInvalidPath("//example.com/credentials");
  }

  @Test
  void concurrentResolutionCollapsesToOneMetadataFetch() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server =
        credentialsServer(
            requests, request -> credentialsJson(request, Instant.parse("2099-01-01T00:00:00Z")));
    ExecutorService callers = Executors.newFixedThreadPool(8);

    try {
      EcsTaskCredentialsProvider credentials = provider(server);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<AwsCredentials>> results = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        results.add(
            callers.submit(
                () -> {
                  start.await();
                  return credentials.resolveCredentials();
                }));
      }

      start.countDown();
      for (Future<AwsCredentials> result : results) {
        assertEquals("container-access-key-1", result.get(5, TimeUnit.SECONDS).accessKeyId());
      }
      assertEquals(1, requests.get());
    } finally {
      callers.shutdownNow();
      boolean terminated = callers.awaitTermination(5, TimeUnit.SECONDS);
      server.stop(0);
      assertTrue(terminated);
    }
  }

  private static void assertMissingField(String response, String field) throws Exception {
    HttpServer server = responseServer(200, request -> response);
    try {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> provider(server).resolveCredentials());
      assertEquals("ECS task credentials response is missing " + field, failure.getMessage());
    } finally {
      server.stop(0);
    }
  }

  private static void assertInvalidPath(String path) {
    EcsTaskCredentialsProvider credentials =
        new EcsTaskCredentialsProvider(
            HttpClient.newHttpClient(), new ObjectMapper(), () -> path, FIXED_CLOCK, null);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, credentials::resolveCredentials);
    assertEquals(
        "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI is missing or invalid", failure.getMessage());
  }

  private static EcsTaskCredentialsProvider provider(HttpServer server) {
    URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/credentials");
    return new EcsTaskCredentialsProvider(
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        () -> "/credentials",
        FIXED_CLOCK,
        endpoint);
  }

  private static HttpServer credentialsServer(AtomicInteger requests, IntFunction<String> response)
      throws Exception {
    return responseServer(200, request -> response.apply(requests.incrementAndGet()));
  }

  private static HttpServer responseServer(int status, IntFunction<String> response)
      throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/credentials",
        exchange -> {
          byte[] body = response.apply(requests.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static String credentialsJson(int request, Instant expiration) {
    return """
        {
          "AccessKeyId": "container-access-key-%d",
          "SecretAccessKey": "container-secret-key",
          "Token": "container-session-token",
          "Expiration": "%s"
        }
        """
        .formatted(request, expiration);
  }
}
