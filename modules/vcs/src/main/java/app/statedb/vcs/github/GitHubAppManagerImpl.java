package app.statedb.vcs.github;

import app.statedb.persistence.vcs.GitHubAppManager;
import app.statedb.persistence.vcs.VcsAppRegistry;
import app.statedb.persistence.vcs.VcsAppRegistry.AppCredentials;
import app.statedb.persistence.vcs.VcsAppRegistry.AppRegistration;
import app.statedb.persistence.vcs.VcsAppRegistry.AppStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** GitHub implementation of runtime App registration, installation verification, and discovery. */
@DefaultBean
@ApplicationScoped
public class GitHubAppManagerImpl implements GitHubAppManager {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final Pattern ORGANIZATION =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
  private static final Pattern MANIFEST_CODE = Pattern.compile("[A-Za-z0-9_-]{20,200}");
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 20;

  private final HttpClient http;
  private final ObjectMapper json;
  private final VcsAppRegistry registrations;
  private final URI apiBase;
  private final URI webBase;
  private final Clock clock;

  @Inject
  GitHubAppManagerImpl(
      ObjectMapper json,
      VcsAppRegistry registrations,
      @ConfigProperty(name = "statedb.github.api-base", defaultValue = "https://api.github.com")
          URI apiBase,
      @ConfigProperty(name = "statedb.github.web-base", defaultValue = "https://github.com")
          URI webBase) {
    this(
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        json,
        registrations,
        apiBase,
        webBase,
        Clock.systemUTC());
  }

  GitHubAppManagerImpl(
      HttpClient http,
      ObjectMapper json,
      VcsAppRegistry registrations,
      URI apiBase,
      URI webBase,
      Clock clock) {
    this.http = http;
    this.json = json;
    this.registrations = registrations;
    this.apiBase = apiBase;
    this.webBase = webBase;
    this.clock = clock;
  }

  @Override
  public ManifestSubmission manifest(URI publicBaseUri, String state, String githubOrganization) {
    URI base = normalizedBase(publicBaseUri);
    String path = "/settings/apps/new";
    if (githubOrganization != null && !githubOrganization.isBlank()) {
      String organization = githubOrganization.strip();
      if (!ORGANIZATION.matcher(organization).matches()) {
        throw new IllegalArgumentException("GitHub organization is not a valid account name");
      }
      path = "/organizations/" + organization + "/settings/apps/new";
    }
    String action =
        webBase.resolve(path).toString()
            + "?state="
            + URLEncoder.encode(state, StandardCharsets.UTF_8);

    ObjectNode manifest = json.createObjectNode();
    manifest.put("name", defaultName(base));
    manifest.put("url", base.toString());
    manifest.put("redirect_url", base.resolve("api/admin/vcs/github/manifest/callback").toString());
    manifest.put("setup_url", base.resolve("api/vcs/github/setup").toString());
    manifest.putArray("callback_urls").add(base.resolve("api/vcs/github/callback").toString());
    manifest.put("setup_on_update", true);
    manifest.put("description", "Publishes statedb Pulumi previews to pull requests.");
    manifest.put("public", true);
    manifest.put("request_oauth_on_install", false);
    ObjectNode permissions = manifest.putObject("default_permissions");
    permissions.put("pull_requests", "write");
    manifest.putArray("default_events");
    return new ManifestSubmission(action, write(manifest));
  }

  @Override
  public AppStatus completeManifest(String code) {
    if (code == null || !MANIFEST_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("GitHub manifest code is invalid");
    }
    HttpRequest request =
        baseRequest(apiBase.resolve("/app-manifests/" + code + "/conversions"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    JsonNode response = send(request);
    JsonNode owner = response.path("owner");
    AppRegistration registration =
        new AppRegistration(
            "github.com",
            requiredLong(response, "id"),
            requiredText(response, "slug"),
            requiredText(owner, "login"),
            requiredText(response, "html_url"),
            requiredText(response, "pem"),
            requiredText(response, "client_id"),
            requiredText(response, "client_secret"));
    return registrations.replace(registration);
  }

  @Override
  public Optional<AppStatus> status() {
    return registrations.status("github.com");
  }

  @Override
  public Installation verifyInstallation(long installationId) {
    AppCredentials credentials = credentials();
    HttpRequest request =
        baseRequest(apiBase.resolve("/app/installations/" + installationId))
            .header("Authorization", "Bearer " + jwt(credentials))
            .GET()
            .build();
    JsonNode response = send(request);
    long verifiedId = requiredLong(response, "id");
    if (verifiedId != installationId) {
      throw new IllegalStateException("GitHub returned a different installation id");
    }
    return installation(response, credentials.appId());
  }

  @Override
  public URI installationUrl(String state) {
    String slug = credentials().status().slug();
    return webBase.resolve("/apps/" + encode(slug) + "/installations/new?state=" + encode(state));
  }

  @Override
  public URI authorizationUrl(URI callback, String state, String verifier) {
    String challenge;
    try {
      challenge =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
    return webBase.resolve(
        "/login/oauth/authorize?client_id="
            + encode(credentials().clientId())
            + "&redirect_uri="
            + encode(callback.toString())
            + "&state="
            + encode(state)
            + "&code_challenge_method=S256&code_challenge="
            + encode(challenge));
  }

  @Override
  public Installation completeConnection(
      String code, URI callback, String verifier, long installationId, int appVersion) {
    AppCredentials credentials = credentials();
    if (credentials.version() != appVersion
        || code == null
        || code.isBlank()
        || code.length() > 512) {
      throw new IllegalArgumentException(
          "GitHub authorization expired. Start Connect GitHub again.");
    }
    String form =
        "client_id="
            + encode(credentials.clientId())
            + "&client_secret="
            + encode(credentials.clientSecret())
            + "&code="
            + encode(code)
            + "&redirect_uri="
            + encode(callback.toString())
            + "&code_verifier="
            + encode(verifier);
    JsonNode authorization =
        send(
            HttpRequest.newBuilder(webBase.resolve("/login/oauth/access_token"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
    if (authorization.has("error")) {
      throw new IllegalArgumentException(
          "GitHub authorization was denied or expired. Start Connect GitHub again.");
    }
    String token = requiredText(authorization, "access_token");
    // This token exists only during verification. It never enters a URL, browser response, or DB.
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode items =
          send(baseRequest(apiBase.resolve("/user/installations?per_page=100&page=" + page))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build())
              .path("installations");
      if (!items.isArray())
        throw new IllegalStateException("GitHub user installations are malformed");
      for (JsonNode item : items) {
        if (item.path("id").asLong() == installationId) {
          return installation(item, credentials.appId());
        }
      }
      if (items.size() < PAGE_SIZE) break;
    }
    throw new app.statedb.persistence.RequestValidationException(
        "This GitHub account cannot access the selected App installation.");
  }

  private Installation installation(JsonNode response, long appId) {
    if (response.path("app_id").asLong() != appId
        || !response.path("suspended_at").isNull() && !response.path("suspended_at").isMissingNode()
        || !"write".equals(response.path("permissions").path("pull_requests").asText())) {
      throw new app.statedb.persistence.RequestValidationException(
          "GitHub installation is suspended, belongs to another App, or lacks pull request write"
              + " access.");
    }
    JsonNode account = response.path("account");
    long id = requiredLong(response, "id");
    String login = requiredText(account, "login");
    String type = requiredText(account, "type");
    String managementPath =
        "Organization".equals(type)
            ? "/organizations/" + encode(login) + "/settings/installations/" + id
            : "/settings/installations/" + id;
    return new Installation(
        id,
        login,
        type,
        requiredText(response, "repository_selection"),
        webBase.resolve(managementPath).toString());
  }

  private static String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private String jwt(AppCredentials credentials) {
    return GitHubAppJwt.sign(credentials.appId(), credentials.privateKeyPem(), clock.instant());
  }

  private AppCredentials credentials() {
    return registrations
        .credentials("github.com")
        .orElseThrow(() -> new IllegalStateException("GitHub App is not configured"));
  }

  private HttpRequest.Builder baseRequest(URI uri) {
    return HttpRequest.newBuilder(uri)
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28");
  }

  private JsonNode send(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode body =
          response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new GitHubApiException(
            response.statusCode(), body.path("message").asText("request failed"));
      }
      return body;
    } catch (GitHubApiException failure) {
      throw failure;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling GitHub", failure);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not call GitHub", failure);
    }
  }

  private String write(JsonNode value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not serialize a GitHub request", failure);
    }
  }

  private static URI normalizedBase(URI raw) {
    if (raw == null || !raw.isAbsolute() || raw.getHost() == null) {
      throw new IllegalArgumentException("public base URL must be absolute");
    }
    String value = raw.toString();
    return URI.create(value.endsWith("/") ? value : value + "/");
  }

  private static String defaultName(URI base) {
    String host = base.getHost().replaceAll("[^A-Za-z0-9-]", "-");
    String name = "statedb-" + host;
    return name.substring(0, Math.min(name.length(), 100));
  }

  private static String requiredText(JsonNode object, String name) {
    String value = object.path(name).asText("");
    if (value.isBlank()) {
      throw new IllegalStateException("GitHub response is missing " + name);
    }
    return value;
  }

  private static long requiredLong(JsonNode object, String name) {
    long value = object.path(name).asLong(0);
    if (value <= 0) {
      throw new IllegalStateException("GitHub response is missing " + name);
    }
    return value;
  }
}
