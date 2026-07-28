package io.statedb.app.contract;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.Workspace;
import io.statedb.testsupport.PulumiProject;
import io.statedb.testsupport.RepoRoot;
import io.statedb.testsupport.TestIdentity;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Replays Pulumi CLI recorder fixtures against the running {@code @QuarkusTest} server.
 *
 * <p>Session replay ({@link #commands}) chains command directories in recorded order, sends each
 * recorded exchange as-is, and reconciles the recorder stub's deterministic ids with the live
 * server's generated ones: when a response returns an {@code updateID} or lease {@code token}, the
 * recorded→live mapping is learned and substituted into the paths and {@code Authorization} headers
 * of subsequent requests. Lease-scoped routes (sub-paths of {@code .../update/{updateID}})
 * authenticate with {@code update-token <live lease>}; everything else uses {@code token
 * <rawToken>}.
 *
 * <p>Exchanges against the secrets encrypt/decrypt endpoints are skipped: the recorded ciphertexts
 * were minted by the recorder stub and the live server cannot decrypt them. Secrets round-trips are
 * covered by {@code SecretsFixtureReplayTest} and the CLI smoke tests.
 */
public final class FixtureReplay {

  public static final String DEFAULT_TOKEN = "fixture-replay-token";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FIXTURE_ROOT = "tools/pulumi/recorder/fixtures/by-command";
  private static final String PULUMI_BASE_PATH = "/pulumi";
  private static final String MASKS_RESOURCE = "contract/field-masks.yaml";

  private static final Pattern UPDATE_PATH =
      Pattern.compile("^/api/stacks/[^/]+/[^/]+/[^/]+/update/([^/]+)(/.*)?$");
  private static final Pattern STACK_PATH =
      Pattern.compile("^/api/stacks/([^/]+)/([^/]+)/([^/]+)$");
  private static final Pattern UPDATE_STACK_PATH =
      Pattern.compile("^/api/stacks/([^/]+)/([^/]+)/([^/]+)/update/([^/]+)(/.*)?$");
  private static final Pattern FORCED_CANCEL_PATH =
      Pattern.compile("^/api/stacks/([^/]+)/([^/]+)/([^/]+)/update/([^/]+)/cancel$");
  private static final Set<String> SECRETS_PATH_SUFFIXES =
      Set.of("/batch-decrypt", "/encrypt", "/decrypt/log-batch-decryption");

  private final List<String> commands;
  private final String rawToken;
  private final ResponseMasks masks;

  // Session state: recorded id -> live id, and recorded updateID -> live lease token.
  private final Map<String, String> idSubstitutions = new HashMap<>();
  private final Map<String, String> leaseByRecordedUpdate = new HashMap<>();
  private final Map<String, String> activeUpdateByStack = new HashMap<>();

  private FixtureReplay(List<String> commands, String rawToken) {
    this.commands = commands;
    this.rawToken = rawToken;
    this.masks = ResponseMasks.load();
  }

  public static FixtureReplay commands(String... commands) {
    return new FixtureReplay(List.of(commands), DEFAULT_TOKEN);
  }

  public FixtureReplay withToken(String rawToken) {
    return new FixtureReplay(commands, rawToken);
  }

  public void replay() {
    for (String command : commands) {
      List<Path> fixtures = fixturesFor(command);
      assertFalse(
          fixtures.isEmpty(),
          () -> "No recorder fixtures found for command " + command + " under " + fixtureRoot());

      seedActiveUpdate(fixtures);

      for (Path fixture : fixtures) {
        replayFixture(fixture);
      }
    }
  }

  /**
   * Re-issues a recorded {@code stack export} against the versioned export path ({@code pulumi
   * stack export --version N}), asserting the recorded response comes back unchanged, and returns
   * the deployment for further assertions.
   *
   * <p>A recorded export is the head state at the moment it was taken, so it is also the deployment
   * the update before it produced. Replaying it as a historical read once later updates have moved
   * the stack on checks the bounded replay against real recorded Pulumi state, without recording a
   * second session to hold the answer.
   */
  public JsonNode assertExportMatchesAtVersion(String command, int version) {
    Path fixture = exportFixture(command);
    try {
      JsonNode exchange = JSON.readTree(fixture.toFile());
      String target = targetUrl(exchange) + "/" + version;
      Response response = given().header("Authorization", "token " + rawToken).when().get(target);
      assertEquals(200, response.statusCode(), target + " status");
      assertJsonMatches(exchange.required("response"), response.asString(), fixture, false);
      return JSON.readTree(response.asString());
    } catch (IOException e) {
      fail("Could not replay fixture " + fixture + ": " + e.getMessage());
      throw new IllegalStateException("unreachable");
    }
  }

  /** The head deployment, for contrast with the historical reads above. */
  public JsonNode exportHead(String org, String project, String stack) {
    String target =
        PULUMI_BASE_PATH + "/api/stacks/" + org + "/" + project + "/" + stack + "/export";
    Response response = given().header("Authorization", "token " + rawToken).when().get(target);
    assertEquals(200, response.statusCode(), target + " status");
    try {
      return JSON.readTree(response.asString());
    } catch (IOException e) {
      fail("Could not parse " + target + ": " + e.getMessage());
      throw new IllegalStateException("unreachable");
    }
  }

  // The first export a command makes is the head state before that command changes anything — for
  // a destroy, the deployment the update before it left behind.
  private static Path exportFixture(String command) {
    for (Path fixture : fixturesFor(command)) {
      try {
        JsonNode exchange = JSON.readTree(fixture.toFile());
        if ("GET".equalsIgnoreCase(exchange.required("method").asText())
            && exchange.required("path").asText().endsWith("/export")) {
          return fixture;
        }
      } catch (IOException e) {
        fail("Could not read fixture " + fixture + ": " + e.getMessage());
      }
    }
    fail(command + " recorded no export");
    throw new IllegalStateException("unreachable");
  }

  /**
   * Starts a live update and acquires its lease before a command that forcibly cancels one, so the
   * stack is genuinely mid-update when the recorded cancel arrives.
   *
   * <p>This mirrors the recorder's own out-of-band {@code POST /meta/active-update}: no pulumi
   * command leaves an update in flight — every update the CLI starts runs to completion — so
   * neither the recorded fixtures nor a replay of them can reach that state by themselves. A
   * recorded forced cancel therefore implies a seeded update; the recorded {@code updateID} is
   * mapped onto the live one exactly as if a fixture response had returned it.
   */
  private void seedActiveUpdate(List<Path> fixtures) {
    Matcher cancel = forcedCancelPath(fixtures);
    if (cancel == null || idSubstitutions.containsKey(cancel.group(4))) {
      return;
    }

    String org = cancel.group(1);
    String project = cancel.group(2);
    String stack = cancel.group(3);
    String recordedUpdateId = cancel.group(4);
    String stackPath = PULUMI_BASE_PATH + "/api/stacks/" + org + "/" + project + "/" + stack;

    String liveUpdateId =
        given()
            .header("Authorization", "token " + rawToken)
            .contentType("application/json")
            .body(Map.of("name", project, "runtime", "yaml"))
            .when()
            .post(stackPath + "/update")
            .then()
            .statusCode(200)
            .extract()
            .path("updateID");

    given()
        .header("Authorization", "token " + rawToken)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post(stackPath + "/update/" + liveUpdateId)
        .then()
        .statusCode(200);

    idSubstitutions.put(recordedUpdateId, liveUpdateId);
  }

  private Matcher forcedCancelPath(List<Path> fixtures) {
    for (Path fixture : fixtures) {
      try {
        JsonNode exchange = JSON.readTree(fixture.toFile());
        if (!"POST".equalsIgnoreCase(exchange.required("method").asText())) {
          continue;
        }
        Matcher matcher = FORCED_CANCEL_PATH.matcher(exchange.required("path").asText());
        if (matcher.matches()) {
          return matcher;
        }
      } catch (IOException e) {
        fail("Could not read fixture " + fixture + ": " + e.getMessage());
      }
    }
    return null;
  }

  private void replayFixture(Path fixture) {
    try {
      JsonNode exchange = JSON.readTree(fixture.toFile());
      String method = exchange.required("method").asText();
      String recordedPath = exchange.required("path").asText();
      if (isSecretsExchange(recordedPath)) {
        return;
      }
      String target = substitute(targetUrl(exchange));

      RequestSpecification request = given();
      addHeaders(request, exchange.path("headers"), method, recordedPath, fixture);
      if (exchange.has("body_b64")) {
        request.body(Base64.getDecoder().decode(exchange.required("body_b64").asText()));
      } else if (exchange.has("body_raw")) {
        request.body(exchange.required("body_raw").asText());
      } else if (exchange.has("body")) {
        request.body(JSON.writeValueAsString(exchange.required("body")));
      }

      Response response = request.when().request(method, target);
      int expectedStatus = exchange.required("status_code").asInt();
      assertEquals(expectedStatus, response.statusCode(), fixture + " status");

      if (exchange.has("response") && !isStubFillerBody(method, recordedPath)) {
        learnSubstitutions(exchange.required("response"), response.asString(), recordedPath);
        learnActiveUpdate(method, recordedPath);
        JsonNode expected = expectedResponse(exchange.required("response"), method, recordedPath);
        if (isOpLifecycle(method, recordedPath)) {
          expected = stripRootVersion(expected);
        }
        assertJsonMatches(
            expected, response.asString(), fixture, isOpLifecycle(method, recordedPath));
      }
    } catch (IOException e) {
      fail("Could not replay fixture " + fixture + ": " + e.getMessage());
    }
  }

  private JsonNode expectedResponse(JsonNode expected, String method, String recordedPath) {
    JsonNode copy = expected.deepCopy();
    if (!"GET".equalsIgnoreCase(method) || !(copy instanceof ObjectNode object)) {
      return copy;
    }

    Matcher stack = STACK_PATH.matcher(recordedPath);
    if (!stack.matches()) {
      return copy;
    }
    String expectedActiveUpdate = object.path("activeUpdate").asText("");
    if (!expectedActiveUpdate.isEmpty()) {
      object.put(
          "activeUpdate", idSubstitutions.getOrDefault(expectedActiveUpdate, expectedActiveUpdate));
      return copy;
    }
    String activeUpdate =
        activeUpdateByStack.get(stackKey(stack.group(1), stack.group(2), stack.group(3)));
    if (activeUpdate != null) {
      object.put("activeUpdate", activeUpdate);
    }
    return copy;
  }

  // The recorder stub did not track update history or the user-stacks index; those GET responses
  // are filler the CLI ignored, not contract (the real shapes are pinned by
  // StackHistoryContractTest and UserStacksContractTest). Status code is still asserted above.
  private static boolean isStubFillerBody(String method, String recordedPath) {
    return "GET".equalsIgnoreCase(method)
        && (recordedPath.endsWith("/updates")
            || recordedPath.endsWith("/update")
            || recordedPath.startsWith("/api/user/stacks"));
  }

  // Create/start responses carry a root "version" the stub hardcoded to 1; the live value is
  // server-generated (0 for dry runs, the applied sequence otherwise) and CLI-invisible at this
  // point, so it is excluded from the comparison.
  private static boolean isOpLifecycle(String method, String recordedPath) {
    if (!"POST".equalsIgnoreCase(method)) {
      return false;
    }
    return recordedPath.matches(
            "^/api/stacks/[^/]+/[^/]+/[^/]+/(update|preview|refresh|destroy|import)$")
        || UPDATE_PATH.matcher(recordedPath).matches();
  }

  private static JsonNode stripRootVersion(JsonNode node) {
    JsonNode copy = node.deepCopy();
    if (copy instanceof ObjectNode object) {
      object.remove("version");
    }
    return copy;
  }

  private void addHeaders(
      RequestSpecification request,
      JsonNode headers,
      String method,
      String recordedPath,
      Path fixture) {
    headers
        .fields()
        .forEachRemaining(
            entry -> {
              String name = entry.getKey();
              if ("Content-Length".equalsIgnoreCase(name)
                  || "Accept-Encoding".equalsIgnoreCase(name)) {
                return;
              }
              if ("Authorization".equalsIgnoreCase(name)) {
                request.header(name, authorization(method, recordedPath, fixture));
              } else {
                request.header(name, entry.getValue().asText());
              }
            });
  }

  // Mirrors LeaseRouteMatcher: lease-write sub-paths of .../update/{updateID} (and DELETE on the
  // bare path) authenticate with the lease token. POST on the bare path is acquire, and
  // POST .../cancel is forced cancellation by a non-holder; both use the regular token scheme.
  private String authorization(String method, String recordedPath, Path fixture) {
    Matcher matcher = UPDATE_PATH.matcher(recordedPath);
    if (matcher.matches()) {
      boolean deeper = matcher.group(2) != null && !matcher.group(2).isEmpty();
      if (deeper || "DELETE".equalsIgnoreCase(method)) {
        String recordedUpdateId = matcher.group(1);
        if ("POST".equalsIgnoreCase(method) && "/cancel".equals(matcher.group(2))) {
          return "token " + rawToken;
        }
        String lease = leaseByRecordedUpdate.get(recordedUpdateId);
        assertFalse(
            lease == null,
            () ->
                fixture
                    + " is lease-scoped but no lease was learned for recorded update "
                    + recordedUpdateId
                    + " — did the session include the update-start exchange?");
        return "update-token " + lease;
      }
    }
    return "token " + rawToken;
  }

  private String substitute(String target) {
    String result = target;
    for (Map.Entry<String, String> substitution : idSubstitutions.entrySet()) {
      result = result.replace(substitution.getKey(), substitution.getValue());
    }
    return result;
  }

  // The recorder stub minted deterministic ids (update-0001, lease-0001); the live server mints
  // real ones. Learn recorded -> live from response roots so later paths and lease headers line up.
  private void learnSubstitutions(JsonNode expected, String actualBody, String recordedPath)
      throws IOException {
    if (actualBody == null || actualBody.isBlank()) {
      return;
    }
    JsonNode actual = JSON.readTree(actualBody);

    String expectedUpdateId = expected.path("updateID").asText("");
    String actualUpdateId = actual.path("updateID").asText("");
    if (!expectedUpdateId.isEmpty() && !actualUpdateId.isEmpty()) {
      idSubstitutions.put(expectedUpdateId, actualUpdateId);
    }

    String actualToken = actual.path("token").asText("");
    if (!actualToken.isEmpty()) {
      Matcher matcher = UPDATE_PATH.matcher(recordedPath);
      if (matcher.matches()) {
        leaseByRecordedUpdate.put(matcher.group(1), actualToken);
      }
    }
  }

  private void learnActiveUpdate(String method, String recordedPath) {
    Matcher matcher = UPDATE_STACK_PATH.matcher(recordedPath);
    if (!matcher.matches()) {
      return;
    }

    String stackKey = stackKey(matcher.group(1), matcher.group(2), matcher.group(3));
    String recordedUpdateId = matcher.group(4);
    String liveUpdateId = idSubstitutions.getOrDefault(recordedUpdateId, recordedUpdateId);
    String subPath = matcher.group(5);
    if ("POST".equalsIgnoreCase(method) && subPath == null) {
      activeUpdateByStack.put(stackKey, liveUpdateId);
    } else if (("DELETE".equalsIgnoreCase(method) && subPath == null)
        || ("POST".equalsIgnoreCase(method)
            && ("/complete".equals(subPath) || "/cancel".equals(subPath)))) {
      activeUpdateByStack.remove(stackKey, liveUpdateId);
    }
  }

  private static String stackKey(String org, String project, String stack) {
    return org + "/" + project + "/" + stack;
  }

  private static boolean isSecretsExchange(String recordedPath) {
    return SECRETS_PATH_SUFFIXES.stream().anyMatch(recordedPath::endsWith);
  }

  private void assertJsonMatches(
      JsonNode expected, String actualBody, Path fixture, boolean stripVersion) throws IOException {
    if (expected.isNull() && (actualBody == null || actualBody.isBlank())) {
      return;
    }
    JsonNode actual =
        actualBody == null || actualBody.isBlank()
            ? JSON.createObjectNode()
            : JSON.readTree(actualBody);
    if (stripVersion) {
      actual = stripRootVersion(actual);
    }
    JsonNode maskedExpected = masks.apply(expected);
    JsonNode maskedActual = masks.apply(actual);
    assertEquals(
        maskedExpected,
        maskedActual,
        () ->
            fixture
                + " response JSON\nexpected:\n"
                + pretty(maskedExpected)
                + "\nactual:\n"
                + pretty(maskedActual));
  }

  private static String targetUrl(JsonNode exchange) {
    String path = exchange.required("path").asText();
    if (path.startsWith("/api/")) {
      path = PULUMI_BASE_PATH + path;
    }
    if (exchange.hasNonNull("query") && !exchange.required("query").asText().isBlank()) {
      return path + "?" + exchange.required("query").asText();
    }
    return path;
  }

  private static List<Path> fixturesFor(String command) {
    Path commandDir = fixtureRoot().resolve(command);
    if (!Files.isDirectory(commandDir)) {
      return List.of();
    }
    try (var stream = Files.list(commandDir)) {
      return stream
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Could not list fixtures in " + commandDir, e);
    }
  }

  private static Path fixtureRoot() {
    return RepoRoot.get().resolve(FIXTURE_ROOT);
  }

  private static String pretty(JsonNode node) {
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (IOException e) {
      return node.toString();
    }
  }

  public static void seedIdentity(EntityManager em) {
    TestIdentity.seed(em, DEFAULT_TOKEN);
  }

  public static void seedIdentityWithStack(EntityManager em) {
    TestIdentity.seed(em, DEFAULT_TOKEN);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = Organization.find("name", TestIdentity.ORG).firstResult();
              seedStack(org);
            });
  }

  public static void cleanup(EntityManager em) {
    TestIdentity.cleanup(em);
  }

  private static void seedStack(Organization org) {
    Project project = new Project();
    project.orgId = org.id;
    project.name = "recorder-test";
    project.persistAndFlush();

    Workspace workspace = new Workspace();
    workspace.orgId = org.id;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persistAndFlush();

    PulumiProject pulumiProject = PulumiProject.recorder();

    Stack stack = new Stack();
    stack.orgId = org.id;
    stack.projectId = project.id;
    stack.name = "dev";
    stack.workspaceId = workspace.id;
    stack.tags =
        Map.of(
            "pulumi:description", pulumiProject.description(),
            "pulumi:project", pulumiProject.name(),
            "pulumi:runtime", pulumiProject.runtime());
    stack.persistAndFlush();

    StackState state = new StackState();
    state.stackId = stack.id;
    state.persistAndFlush();
  }

  /**
   * Masks fields whose values are not under test (timestamps, generated IDs, lock tokens). Two
   * styles are supported:
   *
   * <ul>
   *   <li>{@code fieldNames} — strip every occurrence of a given object key anywhere in the tree.
   *       Suits values that legitimately appear in multiple places (e.g. {@code timestamp}).
   *   <li>{@code paths} — JSON Pointer expressions (RFC 6901). Surgical: only the field at the
   *       given pointer is removed. Use this when a field name has a legitimate value elsewhere in
   *       the response.
   * </ul>
   */
  private record ResponseMasks(Set<String> fieldNames, List<JsonPointer> paths) {

    static ResponseMasks load() {
      try (InputStream in =
          FixtureReplay.class.getClassLoader().getResourceAsStream(MASKS_RESOURCE)) {
        if (in == null) {
          return new ResponseMasks(Set.of(), List.of());
        }
        Object loaded = new Yaml(new LoaderOptions()).load(in);
        if (!(loaded instanceof Map<?, ?> config)) {
          return new ResponseMasks(Set.of(), List.of());
        }
        return new ResponseMasks(parseFieldNames(config), parsePaths(config));
      } catch (IOException e) {
        throw new IllegalStateException("Could not load " + MASKS_RESOURCE, e);
      }
    }

    private static Set<String> parseFieldNames(Map<?, ?> config) {
      Object raw = config.get("fieldNames");
      if (!(raw instanceof List<?> list)) {
        return Set.of();
      }
      Set<String> names = new HashSet<>();
      for (Object field : list) {
        names.add(String.valueOf(field));
      }
      return Set.copyOf(names);
    }

    private static List<JsonPointer> parsePaths(Map<?, ?> config) {
      Object raw = config.get("paths");
      if (!(raw instanceof List<?> list)) {
        return List.of();
      }
      List<JsonPointer> pointers = new ArrayList<>(list.size());
      for (Object path : list) {
        pointers.add(JsonPointer.compile(String.valueOf(path)));
      }
      return List.copyOf(pointers);
    }

    JsonNode apply(JsonNode input) {
      JsonNode copy = input.deepCopy();
      stripByName(copy);
      for (JsonPointer pointer : paths) {
        stripAtPointer(copy, pointer);
      }
      return copy;
    }

    private void stripByName(JsonNode node) {
      if (node instanceof ObjectNode object) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          if (fieldNames.contains(field.getKey())) {
            fields.remove();
          } else {
            stripByName(field.getValue());
          }
        }
      } else if (node.isArray()) {
        node.forEach(this::stripByName);
      }
    }

    private static void stripAtPointer(JsonNode root, JsonPointer pointer) {
      JsonNode parent = root.at(pointer.head());
      if (parent instanceof ObjectNode object) {
        object.remove(pointer.last().getMatchingProperty());
      }
    }
  }
}
