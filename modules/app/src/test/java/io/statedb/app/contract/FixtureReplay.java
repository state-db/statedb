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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Replays Pulumi CLI recorder fixtures against the running {@code @QuarkusTest} server. */
public final class FixtureReplay {

  public static final String DEFAULT_TOKEN = "fixture-replay-token";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FIXTURE_ROOT = "tools/pulumi/recorder/fixtures/by-command";
  private static final String PULUMI_BASE_PATH = "/pulumi";
  private static final String MASKS_RESOURCE = "contract/field-masks.yaml";

  private final String command;
  private final String rawToken;
  private final ResponseMasks masks;

  private FixtureReplay(String command, String rawToken) {
    this.command = command;
    this.rawToken = rawToken;
    this.masks = ResponseMasks.load();
  }

  public static FixtureReplay command(String command) {
    return new FixtureReplay(command, DEFAULT_TOKEN);
  }

  public FixtureReplay withToken(String rawToken) {
    return new FixtureReplay(command, rawToken);
  }

  public void replay() {
    List<Path> fixtures = fixturesFor(command);
    assertFalse(
        fixtures.isEmpty(),
        () -> "No recorder fixtures found for command " + command + " under " + fixtureRoot());

    for (Path fixture : fixtures) {
      replayFixture(fixture);
    }
  }

  private void replayFixture(Path fixture) {
    try {
      JsonNode exchange = JSON.readTree(fixture.toFile());
      String method = exchange.required("method").asText();
      String target = targetUrl(exchange);

      RequestSpecification request = given();
      addHeaders(request, exchange.path("headers"));
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

      if (exchange.has("response")) {
        assertJsonMatches(exchange.required("response"), response.asString(), fixture);
      }
    } catch (IOException e) {
      fail("Could not replay fixture " + fixture + ": " + e.getMessage());
    }
  }

  private void addHeaders(RequestSpecification request, JsonNode headers) {
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
                request.header(name, "token " + rawToken);
              } else {
                request.header(name, entry.getValue().asText());
              }
            });
  }

  private void assertJsonMatches(JsonNode expected, String actualBody, Path fixture)
      throws IOException {
    JsonNode actual =
        actualBody == null || actualBody.isBlank()
            ? JSON.createObjectNode()
            : JSON.readTree(actualBody);
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
