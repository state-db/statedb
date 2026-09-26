package app.statedb.vcs.github;

import app.statedb.persistence.vcs.ReportPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** GitHub implementation of verified PR resolution, immutable comment history. */
@ApplicationScoped
public class GitHubPublisher implements ReportPublisher {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final int MAX_COMMENT_PAGES = 20;

  private final HttpClient http;
  private final ObjectMapper json;
  private final GitHubTokenSource tokens;
  private final URI apiBase;

  @Inject
  GitHubPublisher(
      ObjectMapper json,
      GitHubTokenSource tokens,
      @ConfigProperty(name = "statedb.github.api-base", defaultValue = "https://api.github.com")
          URI apiBase) {
    this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), json, tokens, apiBase);
  }

  GitHubPublisher(HttpClient http, ObjectMapper json, GitHubTokenSource tokens, URI apiBase) {
    this.http = http;
    this.json = json;
    this.tokens = tokens;
    this.apiBase = apiBase;
  }

  @Override
  public boolean supports(String connectionKind, String host) {
    return "github".equalsIgnoreCase(connectionKind) && "github.com".equalsIgnoreCase(host);
  }

  @Override
  public Optional<PullRequestTarget> resolve(Repository repository, Long prNumber, String headSha) {
    String token = tokens.token(repository);
    JsonNode pull;
    if (prNumber != null) {
      pull = get(repository, token, repoPath(repository) + "/pulls/" + prNumber, true);
      if (pull == null) {
        return Optional.empty();
      }
    } else {
      JsonNode pulls =
          get(
              repository,
              token,
              repoPath(repository) + "/commits/" + headSha + "/pulls?per_page=100",
              true);
      if (pulls == null || !pulls.isArray() || pulls.size() >= 100) {
        return Optional.empty();
      }
      pull = uniqueOpenPull(pulls, headSha);
      if (pull == null) {
        return Optional.empty();
      }
    }
    if (!"open".equals(pull.path("state").asText())
        || !headSha.equalsIgnoreCase(pull.path("head").path("sha").asText())) {
      return Optional.empty();
    }
    long number = pull.path("number").asLong(0);
    String repositoryId = pull.path("base").path("repo").path("id").asText("");
    if (number <= 0 || repositoryId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new PullRequestTarget(repositoryId, number, headSha.toLowerCase()));
  }

  @Override
  public String publishComment(
      Repository repository,
      PullRequestTarget target,
      UUID stackId,
      UUID updateId,
      long generation,
      String body) {
    String token = tokens.token(repository);
    String botLogin = tokens.graphqlBotLogin();
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    String prefix = markerPrefix(stackId);
    String marker = marker(stackId, generation, updateId);
    Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "([0-9]+):([0-9a-f-]{36}) -->\\n");
    List<Comment> comments = new ArrayList<>();
    String cursor = null;
    for (int page = 0; ; page++) {
      requireTime(deadline);
      if (page == MAX_COMMENT_PAGES) {
        throw new Failure(
            "PR exceeds 2000-comment reconciliation limit; no comment created", false, 0);
      }
      ObjectNode variables =
          json.createObjectNode()
              .put("owner", repository.owner())
              .put("name", repository.name())
              .put("number", target.number());
      if (cursor == null) variables.putNull("cursor");
      else variables.put("cursor", cursor);
      JsonNode connection =
          graphql(
                  repository,
                  token,
                  """
                  query($owner: String!, $name: String!, $number: Int!, $cursor: String) {
                    repository(owner: $owner, name: $name) {
                      pullRequest(number: $number) {
                        comments(first: 100, after: $cursor) {
                          nodes { id body isMinimized viewerCanMinimize author { login __typename } }
                          pageInfo { hasNextPage endCursor }
                        }
                      }
                    }
                  }
                  """,
                  variables)
              .path("repository")
              .path("pullRequest")
              .path("comments");
      if (!connection.path("nodes").isArray() || !connection.has("pageInfo")) {
        throw new Failure("GitHub returned incomplete PR comments", false, 0);
      }
      for (JsonNode node : connection.path("nodes")) {
        if (!"Bot".equals(node.path("author").path("__typename").asText())
            || !botLogin.equals(node.path("author").path("login").asText())) continue;
        String text = node.path("body").asText();
        var match = pattern.matcher(text);
        if (match.lookingAt()) {
          try {
            comments.add(
                new Comment(
                    node.path("id").asText(),
                    Long.parseLong(match.group(1)),
                    match.group(2),
                    node.path("isMinimized").asBoolean(),
                    node.path("viewerCanMinimize").asBoolean()));
          } catch (NumberFormatException ignored) {
            // A malformed marker is not one of our reports.
          }
        }
      }
      if (!connection.path("pageInfo").path("hasNextPage").asBoolean()) break;
      cursor = connection.path("pageInfo").path("endCursor").asText();
      if (cursor.isBlank()) throw new Failure("GitHub omitted comment cursor", false, 0);
    }
    Comment current =
        comments.stream()
            .filter(c -> c.updateId.equals(updateId.toString()))
            .min(Comparator.comparing(Comment::id))
            .orElse(null);
    if (current == null) {
      // Check capability before posting whenever an older report needs collapsing.
      if (comments.stream().anyMatch(c -> !c.minimized && !c.canMinimize)) {
        throw new Failure(
            "GitHub App cannot minimize preceding report; check pull_requests:write", false, 0);
      }
      requireTime(deadline);
      JsonNode posted =
          send(
              repository,
              token,
              "POST",
              repoPath(repository) + "/issues/" + target.number() + "/comments",
              json.createObjectNode().put("body", marker + "\n" + body),
              false);
      String id = posted.path("node_id").asText();
      if (id.isBlank()) throw new IllegalStateException("GitHub omitted new comment node_id");
      current = new Comment(id, generation, updateId.toString(), false, true);
      comments.add(current);
    }
    // Out-of-order workers preserve every report, but only the newest run remains current.
    // Stable tie-breaking also collapses duplicate markers left by ambiguous remote outcomes.
    Comment newest =
        comments.stream()
            .max(
                Comparator.comparingLong(Comment::generation)
                    .thenComparing(Comment::id, Comparator.reverseOrder()))
            .orElseThrow();
    for (Comment old : comments) {
      if (old.id.equals(newest.id) || old.minimized) continue;
      if (!old.canMinimize)
        throw new Failure("GitHub App cannot minimize report " + old.id, false, 0);
      requireTime(deadline);
      JsonNode result =
          graphql(
              repository,
              token,
              """
              mutation($id: ID!) {
                minimizeComment(input: {subjectId: $id, classifier: OUTDATED}) {
                  minimizedComment { isMinimized }
                }
              }
              """,
              json.createObjectNode().put("id", old.id));
      if (!result
          .path("minimizeComment")
          .path("minimizedComment")
          .path("isMinimized")
          .asBoolean()) {
        throw new IllegalStateException("GitHub did not confirm comment minimization");
      }
    }
    return current.id;
  }

  private JsonNode graphql(
      Repository repository, String token, String query, ObjectNode variables) {
    ObjectNode request = json.createObjectNode().put("query", query);
    request.set("variables", variables);
    JsonNode response = send(repository, token, "POST", "/graphql", request, false);
    if (response.path("errors").isArray() && !response.path("errors").isEmpty()) {
      boolean retryable = true;
      for (JsonNode error : response.path("errors")) {
        String type = error.path("type").asText();
        retryable &=
            "RATE_LIMITED".equals(type)
                || "INTERNAL".equals(type)
                || "SERVICE_UNAVAILABLE".equals(type);
      }
      throw new Failure("GitHub GraphQL error: " + response.path("errors"), retryable, 60);
    }
    return response.path("data");
  }

  private static void requireTime(long deadline) {
    if (System.nanoTime() >= deadline) {
      throw new Failure(
          "comment reconciliation time budget exhausted; retry resumes minimization", true, 2);
    }
  }

  private record Comment(
      String id, long generation, String updateId, boolean minimized, boolean canMinimize) {}

  /** The widest marker plus its newline: a UUID is fixed width and generation is at most a long. */
  @Override
  public int bodyOverheadBytes() {
    UUID any = new UUID(0, 0);
    return marker(any, Long.MAX_VALUE, any).getBytes(StandardCharsets.UTF_8).length + 1;
  }

  private static String markerPrefix(UUID stackId) {
    return "<!-- statedb-report:" + stackId + ":";
  }

  private static String marker(UUID stackId, long generation, UUID updateId) {
    return markerPrefix(stackId) + generation + ":" + updateId + " -->";
  }

  private JsonNode get(Repository repository, String token, String path, boolean notFoundIsEmpty) {
    return send(repository, token, "GET", path, null, notFoundIsEmpty);
  }

  private JsonNode send(
      Repository repository,
      String token,
      String method,
      String path,
      JsonNode body,
      boolean notFoundIsEmpty) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(apiBase.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .header("X-GitHub-Api-Version", "2022-11-28");
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(write(body)));
    }
    try {
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404 && notFoundIsEmpty) {
        return null;
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        // Classify the HTTP status even when a proxy returns an HTML error page.
        String message = "request failed";
        try {
          message = json.readTree(response.body()).path("message").asText(message);
        } catch (JsonProcessingException ignored) {
          // Never log a raw proxy response or treat a definitive status as a transport failure.
        }
        throw new GitHubApiException(response.statusCode(), message, response.headers());
      }
      return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    } catch (GitHubApiException failure) {
      throw failure;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling GitHub", failure);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Could not call GitHub for repository " + repository.name(), failure);
    }
  }

  private String write(JsonNode value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Could not serialize a GitHub request", failure);
    }
  }

  private static JsonNode uniqueOpenPull(JsonNode pulls, String headSha) {
    JsonNode match = null;
    for (JsonNode pull : pulls) {
      if ("open".equals(pull.path("state").asText())
          && headSha.equalsIgnoreCase(pull.path("head").path("sha").asText())) {
        if (match != null) {
          return null;
        }
        match = pull;
      }
    }
    return match;
  }

  private static String repoPath(Repository repository) {
    if (!repository.owner().matches("[A-Za-z0-9_-]+")
        || !repository.name().matches("[A-Za-z0-9_.-]+")
        || repository.name().equals(".")
        || repository.name().equals("..")) {
      throw new Failure("invalid GitHub repository coordinates", false, 0);
    }
    return "/repos/" + repository.owner() + "/" + repository.name();
  }
}
