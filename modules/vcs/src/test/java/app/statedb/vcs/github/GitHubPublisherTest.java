package app.statedb.vcs.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.vcs.ReportPublisher;
import app.statedb.persistence.vcs.ReportPublisher.PullRequestTarget;
import app.statedb.persistence.vcs.ReportPublisher.Repository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubPublisherTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final UUID STACK = UUID.randomUUID();
  private static final PullRequestTarget TARGET = new PullRequestTarget("4242", 7, SHA);
  private static final Repository REPOSITORY =
      new Repository(UUID.randomUUID(), "github.com", "4242", "state-db", "demo");
  private final ObjectMapper json = new ObjectMapper();
  private final List<ObjectNode> comments = new ArrayList<>();
  private final List<String> methods = new ArrayList<>();
  private HttpServer server;
  private GitHubPublisher publisher;
  private boolean losePostResponse;
  private boolean loseMinimizeResponse;
  private boolean endlessPages;
  private boolean forbidden;
  private int posts;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    publisher =
        new GitHubPublisher(
            HttpClient.newHttpClient(),
            json,
            new GitHubTokenSource() {
              public String token(Repository repository) {
                return "installation-token";
              }

              public String graphqlBotLogin() {
                return "state-db";
              }
            },
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void newRunCreatesNewBodyAndCollapsesPreviousReport() {
    UUID first = UUID.randomUUID();
    String firstId = publish(first, 1, "original");
    assertEquals(firstId, publish(first, 1, "must never overwrite original"));
    publish(UUID.randomUUID(), 2, "new report");
    assertEquals(2, posts);
    assertTrue(comments.getFirst().path("body").asText().endsWith("original"));
    assertTrue(comments.getFirst().path("isMinimized").asBoolean());
    assertFalse(comments.getLast().path("isMinimized").asBoolean());
    assertFalse(methods.contains("PATCH"));
    assertFalse(methods.contains("DELETE"));
  }

  @Test
  void bodyOverheadCoversTheWidestMarker() {
    publish(UUID.randomUUID(), Long.MAX_VALUE, "x");
    int posted = comments.getLast().path("body").asText().getBytes(StandardCharsets.UTF_8).length;
    assertEquals(publisher.bodyOverheadBytes(), posted - 1);
  }

  @Test
  void lostPostResponseReconcilesMarkerBeforePostingAgain() {
    publish(UUID.randomUUID(), 1, "old");
    UUID next = UUID.randomUUID();
    losePostResponse = true;
    assertThrows(GitHubApiException.class, () -> publish(next, 2, "new"));
    publish(next, 2, "new");
    assertEquals(2, posts);
    assertEquals(1, comments.stream().filter(c -> !c.path("isMinimized").asBoolean()).count());
  }

  @Test
  void lostMinimizeResponseResumesWithoutRepostingOrEditing() {
    publish(UUID.randomUUID(), 1, "old");
    UUID next = UUID.randomUUID();
    loseMinimizeResponse = true;
    assertThrows(ReportPublisher.Failure.class, () -> publish(next, 2, "new"));
    publish(next, 2, "new");
    assertEquals(2, posts);
    assertTrue(comments.getFirst().path("isMinimized").asBoolean());
  }

  @Test
  void delayedOlderRunCannotBecomeCurrentAndDuplicateMarkersAreCollapsed() {
    UUID newer = UUID.randomUUID();
    publish(newer, 2, "newer");
    ObjectNode duplicate = comments.getFirst().deepCopy().put("id", "C_duplicate");
    comments.add(duplicate);
    publish(UUID.randomUUID(), 1, "late older report");
    publish(newer, 2, "newer");
    assertEquals(1, comments.stream().filter(c -> !c.path("isMinimized").asBoolean()).count());
    assertFalse(comments.getFirst().path("isMinimized").asBoolean());
  }

  @Test
  void previousAppReportsAreNotMinimized() {
    publish(UUID.randomUUID(), 1, "unrelated");
    comments.getFirst().withObject("author").put("login", "state-db-localhost");
    publish(UUID.randomUUID(), 2, "ours");
    assertFalse(comments.getFirst().path("isMinimized").asBoolean());
    assertEquals(2, posts);
  }

  @Test
  void incompletePaginationFailsClosedBeforePosting() {
    endlessPages = true;
    ReportPublisher.Failure error =
        assertThrows(ReportPublisher.Failure.class, () -> publish(UUID.randomUUID(), 1, "report"));
    assertFalse(error.retryable);
    assertEquals(0, posts);
  }

  @Test
  void permissionDenialIsTerminalEvenWhenGraphqlReturnsHttp200() {
    publish(UUID.randomUUID(), 1, "old");
    forbidden = true;
    ReportPublisher.Failure error =
        assertThrows(ReportPublisher.Failure.class, () -> publish(UUID.randomUUID(), 2, "new"));
    assertFalse(error.retryable);
  }

  @Test
  void checksMinimizationPermissionBeforeCreatingAnotherCurrentComment() {
    publish(UUID.randomUUID(), 1, "old");
    comments.getFirst().put("viewerCanMinimize", false);
    assertThrows(ReportPublisher.Failure.class, () -> publish(UUID.randomUUID(), 2, "new"));
    assertEquals(1, posts);
  }

  @Test
  void resolvesCommitToUniqueMatchingPullRequest() {
    assertEquals(TARGET, publisher.resolve(REPOSITORY, null, SHA).orElseThrow());
    assertTrue(publisher.resolve(REPOSITORY, 7L, "0".repeat(40)).isEmpty());
  }

  @Test
  void httpClassificationDistinguishesRateLimitsFromPermanentErrors() {
    for (int status : List.of(400, 401, 403, 404, 422)) {
      assertFalse(new GitHubApiException(status, "invalid request").retryable);
    }
    for (int status : List.of(408, 429, 500, 502, 503)) {
      assertTrue(new GitHubApiException(status, "unavailable").retryable);
    }
    var rateLimit =
        new GitHubApiException(
            403,
            "secondary rate limit",
            HttpHeaders.of(Map.of("Retry-After", List.of("120")), (a, b) -> true));
    assertTrue(rateLimit.retryable);
    assertEquals(120, rateLimit.retryAfterSeconds);
  }

  private String publish(UUID update, long generation, String body) {
    return publisher.publishComment(REPOSITORY, TARGET, STACK, update, generation, body);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().toString();
    String method = exchange.getRequestMethod();
    methods.add(method);
    assertEquals(
        "Bearer installation-token", exchange.getRequestHeaders().getFirst("Authorization"));
    JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
    ObjectNode response = json.createObjectNode();
    int status = 200;
    if (path.contains("/pulls/7") || path.contains("/commits/")) {
      ObjectNode pull = json.createObjectNode().put("number", 7).put("state", "open");
      pull.putObject("head").put("sha", SHA);
      pull.putObject("base").putObject("repo").put("id", 4242);
      byte[] bytes =
          json.writeValueAsBytes(
              path.contains("/commits/") ? json.createArrayNode().add(pull) : pull);
      respond(exchange, status, bytes);
      return;
    } else if (path.equals("/graphql")) {
      String query = request.path("query").asText();
      if (query.contains("mutation")) {
        assertTrue(query.contains("classifier: OUTDATED"));
        if (forbidden) {
          response.putArray("errors").addObject().put("type", "FORBIDDEN");
        } else {
          comments.stream()
              .filter(c -> c.path("id").equals(request.path("variables").path("id")))
              .forEach(c -> c.put("isMinimized", true));
          response
              .putObject("data")
              .putObject("minimizeComment")
              .putObject("minimizedComment")
              .put("isMinimized", true);
          if (loseMinimizeResponse) {
            loseMinimizeResponse = false;
            response.removeAll();
            response.putArray("errors").addObject().put("type", "INTERNAL");
          }
        }
      } else {
        ObjectNode connection =
            response
                .putObject("data")
                .putObject("repository")
                .putObject("pullRequest")
                .putObject("comments");
        var nodes = connection.putArray("nodes");
        comments.forEach(nodes::add);
        connection.putObject("pageInfo").put("hasNextPage", endlessPages).put("endCursor", "next");
      }
    } else if (method.equals("POST") && path.endsWith("/comments")) {
      posts++;
      ObjectNode comment =
          json.createObjectNode()
              .put("id", "C_" + posts)
              .put("body", request.path("body").asText())
              .put("isMinimized", false)
              .put("viewerCanMinimize", true);
      // GraphQL Bot.login omits the REST-only [bot] suffix.
      comment.putObject("author").put("login", "state-db").put("__typename", "Bot");
      comments.add(comment);
      response.put("node_id", "C_" + posts);
      status = losePostResponse ? 503 : 201;
      losePostResponse = false;
    } else {
      status = 404;
    }
    respond(exchange, status, json.writeValueAsBytes(response));
  }

  private void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
