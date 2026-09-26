package app.statedb.vcs.github;

import app.statedb.persistence.vcs.ReportPublisher.Repository;
import app.statedb.persistence.vcs.VcsAppRegistry;
import app.statedb.persistence.vcs.VcsAppRegistry.AppCredentials;
import app.statedb.persistence.vcs.VcsConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Mints repository-scoped, one-hour GitHub App installation tokens. */
@ApplicationScoped
public class GitHubAppTokenSource implements GitHubTokenSource {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
  private final HttpClient http;
  private final ObjectMapper json;
  private final URI apiBase;
  private final VcsAppRegistry registrations;
  private final Clock clock;
  private final Map<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();

  @Inject
  GitHubAppTokenSource(
      ObjectMapper json,
      @ConfigProperty(name = "statedb.github.api-base", defaultValue = "https://api.github.com")
          URI apiBase,
      VcsAppRegistry registrations) {
    this(
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        json,
        apiBase,
        registrations,
        Clock.systemUTC());
  }

  GitHubAppTokenSource(
      HttpClient http, ObjectMapper json, URI apiBase, VcsAppRegistry registrations, Clock clock) {
    this.http = http;
    this.json = json;
    this.apiBase = apiBase;
    this.registrations = registrations;
    this.clock = clock;
  }

  @Override
  public String graphqlBotLogin() {
    return registrations
        .status("github.com")
        .orElseThrow(
            () ->
                new app.statedb.persistence.vcs.ReportPublisher.Failure(
                    "GitHub App is not configured", false, 0))
        .slug();
  }

  @Override
  public String token(Repository repository) {
    VcsConnection connection = VcsConnection.findById(repository.connectionId());
    if (connection == null || connection.installationId == null) {
      throw new IllegalStateException("GitHub connection has no installation id");
    }
    AppCredentials credentials =
        registrations
            .credentials("github.com")
            .orElseThrow(() -> new IllegalStateException("GitHub App is not configured"));
    long repositoryId = 0;
    if (repository.repositoryId() != null) {
      try {
        repositoryId = Long.parseLong(repository.repositoryId());
      } catch (NumberFormatException failure) {
        throw new IllegalStateException("GitHub repository id is not numeric", failure);
      }
    }
    CacheKey key =
        new CacheKey(
            connection.id,
            connection.version,
            credentials.version(),
            connection.installationId,
            repositoryId);
    Instant now = clock.instant();
    CachedToken cached = cache.get(key);
    if (cached != null && now.isBefore(cached.expiresAt.minus(REFRESH_MARGIN))) {
      return cached.value;
    }
    CachedToken minted = mint(connection.installationId, repositoryId, credentials, now);
    cache.put(key, minted);
    cache
        .keySet()
        .removeIf(
            candidate -> candidate.connectionId.equals(key.connectionId) && !candidate.equals(key));
    return minted.value;
  }

  private CachedToken mint(
      long installationId, long repositoryId, AppCredentials credentials, Instant now) {
    ObjectNode permissions = json.createObjectNode();
    permissions.put("pull_requests", "write");
    ObjectNode body = json.createObjectNode();
    if (repositoryId > 0) {
      body.putArray("repository_ids").add(repositoryId);
    }
    body.set("permissions", permissions);
    HttpRequest request =
        HttpRequest.newBuilder(
                apiBase.resolve("/app/installations/" + installationId + "/access_tokens"))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header(
                "Authorization",
                "Bearer "
                    + GitHubAppJwt.sign(credentials.appId(), credentials.privateKeyPem(), now))
            .header("X-GitHub-Api-Version", "2022-11-28")
            .POST(HttpRequest.BodyPublishers.ofString(write(body)))
            .build();
    JsonNode response = send(request);
    String token = requiredText(response, "token");
    try {
      return new CachedToken(token, Instant.parse(requiredText(response, "expires_at")));
    } catch (DateTimeParseException failure) {
      throw new IllegalStateException("GitHub token response has an invalid expiry", failure);
    }
  }

  private JsonNode send(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        // Classify the HTTP status even when a proxy returns an HTML error page.
        String message = "request failed";
        try {
          message = json.readTree(response.body()).path("message").asText(message);
        } catch (Exception ignored) {
          // Never log a raw proxy response or treat a definitive status as a transport failure.
        }
        throw new GitHubApiException(response.statusCode(), message, response.headers());
      }
      return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    } catch (GitHubApiException failure) {
      throw failure;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while requesting a GitHub installation token", failure);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not request a GitHub installation token", failure);
    }
  }

  private String write(JsonNode value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not serialize a GitHub request", failure);
    }
  }

  private static String requiredText(JsonNode object, String name) {
    String value = object.path(name).asText("");
    if (value.isBlank()) {
      throw new IllegalStateException("GitHub response is missing " + name);
    }
    return value;
  }

  private record CacheKey(
      UUID connectionId,
      int connectionVersion,
      int appVersion,
      long installationId,
      long repositoryId) {}

  private record CachedToken(String value, Instant expiresAt) {}
}
