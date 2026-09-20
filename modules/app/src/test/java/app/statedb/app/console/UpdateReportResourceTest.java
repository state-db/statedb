package app.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.events.EventArchiveFolder;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.events.UpdateEventArchive;
import app.statedb.persistence.events.UpdateEventView;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/updates/{id}/report} end to end: authorization, both formats, and parity across a
 * fold.
 *
 * <p>The authorization cases are the point of the flat, update-keyed route. It carries no org,
 * project or stack, so nothing about the URL constrains which tenant it reads — only the loader
 * does, and if the loader were wrong an update id would be a way to read any stack in the
 * deployment. Those cases are asserted here rather than left to the loader's own unit, because what
 * matters is the status a caller actually receives.
 */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class UpdateReportResourceTest {

  private static final String ORG = "update-report-org";
  private static final String OTHER_ORG = "update-report-other-org";
  private static final String PROJECT = "shop";
  private static final String STACK = "dev";
  private static final String CORPUS = "/report/events-mixed-run.json";
  private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

  @Inject StackCreator stackCreator;
  @Inject EventArchiveFolder folder;

  @BeforeEach
  void seed() {
    cleanup();
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization.delete("name", ORG);
              Organization.delete("name", OTHER_ORG);
            });
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void servesMarkdownByDefaultAndJsonOnRequest() {
    Cookie session = ownerSession(ORG);
    UUID updateId = seedRun(createStack(ORG));

    Response markdown = report(session, updateId, null);
    markdown.then().statusCode(200);
    String body = markdown.jsonPath().getString("body");
    assertTrue(body.contains("### Pulumi `preview`"), body);
    assertTrue(body.contains("aws:rds/instance:Instance"), body);
    assertEquals("+1 ~2 -1 ±1 =1", markdown.jsonPath().getString("summaryLine"));
    assertFalse(markdown.jsonPath().getBoolean("truncated"));

    // The link is built from the request's own base URI, so it is an address the caller can
    // reach, and it targets the canonical console route rather than the redirecting short form.
    assertTrue(
        body.contains("/console/" + ORG + "/" + PROJECT + "/" + STACK + "/previews/" + updateId),
        body);

    Response explicit = report(session, updateId, "markdown");
    assertEquals(body, explicit.jsonPath().getString("body"), "the default is markdown");

    Response json = report(session, updateId, "json");
    json.then().statusCode(200);
    assertEquals("succeeded", json.jsonPath().getString("result"));
    assertEquals(5, json.jsonPath().getList("resources").size());
    assertEquals("preview", json.jsonPath().getString("kind"));
    assertEquals("create", json.jsonPath().getString("resources[0].op"));
    assertEquals("update-replace", json.jsonPath().getString("resources[1].properties[0].kind"));

    // The JSON form is the sanitized model, not the events: no values on it anywhere.
    String raw = json.asString();
    for (String value : List.of("db.t3.micro", "db.t3.small", "production", "ciphertext")) {
      assertFalse(raw.contains(value), "value leaked into the JSON report: " + value);
    }
  }

  /**
   * The failure this endpoint is shaped to avoid. Once folded, a report built by querying {@code
   * update_events} would find only the summary rows the fold left behind and serve a report with no
   * resources in it — a wrong answer with a 200 on it.
   */
  @Test
  void reportsIdenticallyOnceTheRunIsFolded() {
    Cookie session = ownerSession(ORG);
    UUID updateId = seedRun(createStack(ORG));

    String before = report(session, updateId, null).asString();

    QuarkusTransaction.requiringNew().run(() -> folder.fold(updateId, Instant.now()));
    assertNotNull(
        QuarkusTransaction.requiringNew().call(() -> UpdateEventArchive.findById(updateId)),
        "precondition: the run was folded");

    String after = report(session, updateId, null).asString();
    assertEquals(before, after, "a folded run must report identically");
    assertTrue(after.contains("aws:rds/instance:Instance"), "resources survived the fold");
  }

  @Test
  void refusesAnUpdateInAnotherTenantAsIfItDidNotExist() {
    UUID foreignUpdate = seedRun(createStack(OTHER_ORG, true));
    // A member of ORG with no role at all in OTHER_ORG.
    Cookie session = ownerSession(ORG);

    // 404 rather than 403: a 403 would confirm that this update id exists somewhere.
    report(session, foreignUpdate, null).then().statusCode(404);
  }

  @Test
  void refusesAPrivateStackInTheCallersOrgWithoutAReadGrant() {
    Cookie session = session(ORG, OrgMember.Role.member);
    UUID stackId = createStack(ORG);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Stack stack = Stack.findById(stackId);
              stack.visibility = Stack.VISIBILITY_PRIVATE;
            });
    UUID updateId = seedRun(stackId);

    report(session, updateId, null).then().statusCode(404);
  }

  @Test
  void refusesAnUnknownOrMalformedUpdateId() {
    Cookie session = ownerSession(ORG);
    report(session, UUID.randomUUID(), null).then().statusCode(404);
    given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/updates/not-a-uuid/report")
        .then()
        .statusCode(404);
  }

  @Test
  void refusesAnUnauthenticatedCaller() {
    UUID updateId = seedRun(createStack(ORG, true));
    given().when().get("/api/updates/" + updateId + "/report").then().statusCode(401);
  }

  @Test
  void rejectsAnUnknownFormat() {
    Cookie session = ownerSession(ORG);
    UUID updateId = seedRun(createStack(ORG));
    report(session, updateId, "html").then().statusCode(400);
  }

  private Response report(Cookie session, UUID updateId, String format) {
    var request = given().cookie(session.getName(), session.getValue());
    if (format != null) {
      request = request.queryParam("format", format);
    }
    return request.when().get("/api/updates/" + updateId + "/report");
  }

  private UUID createStack(String orgName) {
    return createStack(orgName, false);
  }

  private UUID createStack(String orgName, boolean createOrg) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Organization org = Organization.<Organization>find("name", orgName).firstResult();
              if (org == null && createOrg) {
                org = new Organization();
                org.name = orgName;
                org.displayName = orgName;
                org.persistAndFlush();
              }
              stackCreator.create(org.id, orgName, PROJECT, STACK, Map.of());
              return Stack.<Stack>find("orgId = ?1 and name = ?2", org.id, STACK).firstResult().id;
            });
  }

  private UUID seedRun(UUID stackId) {
    List<JsonNode> corpus = corpus();
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              StackUpdate update = new StackUpdate();
              update.stackId = stackId;
              update.kind = StackUpdate.Kind.PREVIEW;
              update.status = StackUpdate.Status.SUCCEEDED;
              update.dryRun = true;
              update.version = 0;
              update.startedAt = LONG_AGO;
              update.endedAt = LONG_AGO;
              update.persistAndFlush();
              for (JsonNode raw : corpus) {
                UpdateEventView view = new UpdateEventView(raw);
                UpdateEvent event = new UpdateEvent();
                event.stackId = stackId;
                event.updateId = update.id;
                event.sequence = view.sequence();
                event.kind = view.kind();
                event.severity = view.severity();
                event.payload = raw;
                event.persist();
              }
              return update.id;
            });
  }

  private static List<JsonNode> corpus() {
    try (InputStream in = UpdateReportResourceTest.class.getResourceAsStream(CORPUS)) {
      assertNotNull(in, "missing corpus " + CORPUS);
      List<JsonNode> events = new ArrayList<>();
      new ObjectMapper().readTree(in).forEach(events::add);
      return events;
    } catch (Exception e) {
      throw new IllegalStateException("failed to read " + CORPUS, e);
    }
  }

  private static Cookie ownerSession(String orgName) {
    return session(orgName, OrgMember.Role.owner);
  }

  private static Cookie session(String orgName, OrgMember.Role role) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (Organization.find("name", orgName).firstResult() == null) {
                Organization org = new Organization();
                org.name = orgName;
                org.displayName = orgName;
                org.persistAndFlush();
              }
            });
    OidcConsoleSessionFixture.seedProvider();
    Cookie session = OidcConsoleSessionFixture.login();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              User user =
                  User.<User>find(
                          "iss = ?1 and sub = ?2",
                          OidcConsoleSessionFixture.FIXTURE_ISS,
                          OidcConsoleSessionFixture.FIXTURE_SUB)
                      .firstResult();
              Organization org = Organization.<Organization>find("name", orgName).firstResult();
              if (OrgMember.find("orgId = ?1 and userId = ?2", org.id, user.id).firstResult()
                  == null) {
                OrgMember membership = new OrgMember();
                membership.orgId = org.id;
                membership.userId = user.id;
                membership.role = role;
                membership.persistAndFlush();
              }
            });
    return session;
  }
}
