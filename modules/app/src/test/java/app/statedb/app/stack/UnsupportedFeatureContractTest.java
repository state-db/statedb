package app.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import app.statedb.app.contract.PulumiContractIdentity;
import io.quarkus.test.junit.QuarkusTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the "not supported" stubs for the Pulumi Cloud surfaces state-db does not implement —
 * deployments, schedules, webhooks, org governance/search/usage, policy packs, ESC, and the
 * package/template registry. state-db is a state backend, so these features are unsupported and the
 * CLI must get a clear signal rather than a bare 404 with an empty body.
 *
 * <p>The status is deliberately 404 rather than 501; see {@code UnsupportedFeature} for why. The
 * cases below cover both the list paths and the nested write paths, since the wildcard in each
 * {@code @Path} is what makes the whole surface answer consistently. Paths are the ones the real
 * CLI sends, captured by pointing v3.253.0 at a logging stub. The stubs carry no
 * {@code @Authorize}, so the test also confirms {@code TokenAuthFilter} still enforces 401 without
 * a token.
 */
@QuarkusTest
class UnsupportedFeatureContractTest {

  private static final String ORG_NAME = "unsupported-feature-org";
  private static final String RAW_TOKEN = "unsupported-feature-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final String BASE = "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + STACK;
  private static final String ORG_BASE = "/pulumi/api/orgs/" + ORG_NAME;
  private static final String SCHEDULES = BASE + "/deployments/schedules";
  private static final String HOOKS = BASE + "/hooks";

  /** The command that reaches the endpoint, the request, and the message it must produce. */
  private record UnsupportedEndpoint(String command, String method, String path, String message) {
    @Override
    public String toString() {
      return command + " -> " + method + " " + path;
    }
  }

  private static Stream<UnsupportedEndpoint> unsupportedEndpoints() {
    return Stream.of(
        new UnsupportedEndpoint("stack schedule list", "GET", SCHEDULES, "stack schedules"),
        new UnsupportedEndpoint("stack schedule create", "POST", SCHEDULES, "stack schedules"),
        new UnsupportedEndpoint(
            "stack schedule rm", "DELETE", SCHEDULES + "/sched-1", "stack schedules"),
        new UnsupportedEndpoint(
            "stack schedule drift create",
            "POST",
            BASE + "/deployments/drift/schedules",
            "stack schedules"),
        new UnsupportedEndpoint(
            "stack schedule ttl create",
            "POST",
            BASE + "/deployments/ttl/schedules",
            "stack schedules"),
        new UnsupportedEndpoint(
            "stack drift status", "GET", BASE + "/drift/status", "stack drift detection runs"),
        new UnsupportedEndpoint(
            "stack drift list", "GET", BASE + "/drift/runs", "stack drift detection runs"),
        new UnsupportedEndpoint("stack webhook list", "GET", HOOKS, "stack webhooks"),
        new UnsupportedEndpoint("stack webhook new", "POST", HOOKS, "stack webhooks"),
        new UnsupportedEndpoint(
            "deployment list", "GET", BASE + "/deployments", "stack deployments"),
        new UnsupportedEndpoint(
            "deployment run", "POST", BASE + "/deployments", "stack deployments"),
        new UnsupportedEndpoint(
            "deployment settings get",
            "GET",
            BASE + "/deployments/settings",
            "stack deployment settings"),
        new UnsupportedEndpoint(
            "deployment settings edit",
            "PUT",
            BASE + "/deployments/settings",
            "stack deployment settings"),
        new UnsupportedEndpoint(
            "deployment settings destroy",
            "DELETE",
            BASE + "/deployments/settings",
            "stack deployment settings"),
        new UnsupportedEndpoint(
            "deployment settings encrypt",
            "POST",
            BASE + "/deployments/settings/encrypt",
            "stack deployment settings"),
        new UnsupportedEndpoint(
            "org member list", "GET", ORG_BASE + "/members", "organization members"),
        new UnsupportedEndpoint(
            "org member edit", "POST", ORG_BASE + "/members/someone", "organization members"),
        new UnsupportedEndpoint(
            "org role list", "GET", ORG_BASE + "/roles", "organization custom roles"),
        new UnsupportedEndpoint(
            "org role new", "POST", ORG_BASE + "/roles", "organization custom roles"),
        new UnsupportedEndpoint(
            "org webhook list", "GET", ORG_BASE + "/hooks", "organization webhooks"),
        new UnsupportedEndpoint(
            "org webhook new", "POST", ORG_BASE + "/hooks", "organization webhooks"),
        new UnsupportedEndpoint(
            "org audit-log list", "GET", ORG_BASE + "/auditlogs", "organization audit logs"),
        new UnsupportedEndpoint(
            "org usage get",
            "GET",
            ORG_BASE + "/resources/summary",
            "organization resource usage summaries"),
        new UnsupportedEndpoint(
            "org search", "GET", ORG_BASE + "/search/resources", "organization resource searches"),
        new UnsupportedEndpoint(
            "org search --parse",
            "GET",
            ORG_BASE + "/search/resources/parse",
            "organization resource searches"),
        new UnsupportedEndpoint("policy ls", "GET", ORG_BASE + "/policypacks", "policy packs"),
        new UnsupportedEndpoint(
            "policy publish", "POST", ORG_BASE + "/policypacks", "policy packs"),
        new UnsupportedEndpoint(
            "policy publish (complete)",
            "POST",
            ORG_BASE + "/policypacks/pack/1.0.0/complete",
            "policy packs"),
        new UnsupportedEndpoint(
            "env ls", "GET", "/pulumi/api/esc/environments", "ESC environments"),
        new UnsupportedEndpoint(
            "env init", "POST", "/pulumi/api/esc/environments/acme/prod", "ESC environments"),
        new UnsupportedEndpoint(
            "new --list-templates", "GET", "/pulumi/api/registry/templates", "registry templates"),
        new UnsupportedEndpoint(
            "template publish",
            "POST",
            "/pulumi/api/registry/templates/private/acme/tpl/versions",
            "registry templates"),
        new UnsupportedEndpoint(
            "package info", "GET", "/pulumi/api/registry/packages", "registry packages"),
        new UnsupportedEndpoint(
            "package publish",
            "POST",
            "/pulumi/api/registry/packages/private/acme/pkg/versions",
            "registry packages"));
  }

  private static final PulumiContractIdentity IDENTITY =
      new PulumiContractIdentity(
          ORG_NAME,
          "Unsupported Feature Test",
          "unsupported-feature",
          "unsupported@example.com",
          "Unsupported Feature Tester",
          RAW_TOKEN,
          "unsupported-feature");

  @BeforeEach
  void seed() {
    IDENTITY.seed();
  }

  @AfterEach
  void cleanup() {
    IDENTITY.cleanup();
  }

  @ParameterizedTest
  @MethodSource("unsupportedEndpoints")
  void returnsNotSupported(UnsupportedEndpoint endpoint) {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .request(endpoint.method(), endpoint.path())
        .then()
        // 404, not 501: the CLI retries every 5xx four times before showing the message.
        .statusCode(404)
        .body("code", equalTo(404))
        .body("message", equalTo(endpoint.message() + " are not supported by this backend"));
  }

  @ParameterizedTest
  @MethodSource("unsupportedEndpoints")
  void withoutTokenReturns401(UnsupportedEndpoint endpoint) {
    given().when().request(endpoint.method(), endpoint.path()).then().statusCode(401);
  }
}
