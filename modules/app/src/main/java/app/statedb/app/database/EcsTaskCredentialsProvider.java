package app.statedb.app.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

/** Reads and caches the task-role credentials that ECS exposes inside the container. */
@ApplicationScoped
class EcsTaskCredentialsProvider {

  private static final String ECS_CREDENTIALS_ORIGIN = "http://169.254.170.2";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REFRESH_WINDOW = Duration.ofMinutes(5);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Supplier<String> credentialsPath;
  private final Clock clock;
  private final URI fixedEndpoint;

  private volatile CachedCredentials cached;

  @Inject
  EcsTaskCredentialsProvider(ObjectMapper objectMapper) {
    this(
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        objectMapper,
        () -> System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"),
        Clock.systemUTC(),
        null);
  }

  EcsTaskCredentialsProvider(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<String> credentialsPath,
      Clock clock,
      URI fixedEndpoint) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.credentialsPath = credentialsPath;
    this.clock = clock;
    this.fixedEndpoint = fixedEndpoint;
  }

  AwsCredentialsIdentity resolveCredentials() {
    CachedCredentials current = cached;
    if (current != null && current.expiration().isAfter(clock.instant().plus(REFRESH_WINDOW))) {
      return current.identity();
    }
    synchronized (this) {
      current = cached;
      if (current == null || !current.expiration().isAfter(clock.instant().plus(REFRESH_WINDOW))) {
        cached = current = fetchCredentials();
      }
      return current.identity();
    }
  }

  private CachedCredentials fetchCredentials() {
    HttpRequest request =
        HttpRequest.newBuilder(credentialsEndpoint()).timeout(REQUEST_TIMEOUT).GET().build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "ECS task credentials endpoint returned HTTP " + response.statusCode());
      }
      JsonNode body = objectMapper.readTree(response.body());
      AwsSessionCredentialsIdentity identity =
          AwsSessionCredentialsIdentity.create(
              requiredText(body, "AccessKeyId"),
              requiredText(body, "SecretAccessKey"),
              requiredText(body, "Token"));
      return new CachedCredentials(identity, Instant.parse(requiredText(body, "Expiration")));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading ECS task credentials", e);
    } catch (IOException | RuntimeException e) {
      if (e instanceof IllegalStateException illegalStateException) {
        throw illegalStateException;
      }
      throw new IllegalStateException("Could not read ECS task credentials", e);
    }
  }

  private URI credentialsEndpoint() {
    if (fixedEndpoint != null) {
      return fixedEndpoint;
    }
    String path = credentialsPath.get();
    if (path == null || !path.startsWith("/") || path.startsWith("//")) {
      throw new IllegalStateException(
          "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI is missing or invalid");
    }
    return URI.create(ECS_CREDENTIALS_ORIGIN + path);
  }

  private static String requiredText(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalStateException("ECS task credentials response is missing " + field);
    }
    return value.textValue();
  }

  private record CachedCredentials(AwsCredentialsIdentity identity, Instant expiration) {}
}
