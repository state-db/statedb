package app.statedb.app.console;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.events.EventArchiveFixture;
import app.statedb.persistence.events.EventArchiveFolder;
import app.statedb.persistence.events.UpdateEvent;
import app.statedb.persistence.events.UpdateEventArchive;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.update.StackUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Paging parity for the console events endpoint across a fold. The archived stream must produce the
 * same response bodies — same events, same {@code nextAfter} cursors — as the rows it replaced,
 * because {@code run-detail.tsx} pages exactly this way.
 */
@QuarkusTest
@TestProfile(LocalGlassTestSupport.Profile.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class ConsoleRunEventsArchiveTest {

  private static final String ORG = "run-events-archive-org";
  private static final String PROJECT = "project";
  private static final String STACK = "dev";
  private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");
  // Past the endpoint's 500-event page size, so the parity check covers a cursor handoff.
  private static final int EVENT_COUNT = 600;

  @Inject StackCreator stackCreator;
  @Inject EventArchiveFolder folder;

  @BeforeEach
  void seed() {
    cleanup();
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> Organization.delete("name", ORG));
    OidcConsoleSessionFixture.cleanup();
  }

  @Test
  void archivedUpdateServesByteIdenticalPagesAndKeepsSummaryHot() {
    Cookie session = ownerSession();
    UUID stackId = createStack();
    UUID updateId = seedUpdate(stackId);

    List<String> beforeFold = pageThrough(session, updateId);
    assertTrue(beforeFold.size() > 1, "corpus should span more than one page");
    String runDetail = runDetail(session);

    QuarkusTransaction.requiringNew().run(() -> folder.fold(updateId, Instant.now()));
    assertNotNull(
        QuarkusTransaction.requiringNew().call(() -> UpdateEventArchive.findById(updateId)),
        "update was folded");

    assertEquals(beforeFold, pageThrough(session, updateId), "pages differ once archived");

    // The summary is answered from the row the folder left behind, not by inflating the blob.
    assertTrue(
        QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        UpdateEvent.count(
                            "updateId = ?1 and kind = ?2", updateId, UpdateEvent.SUMMARY_KIND))
            > 0,
        "summary rows stay hot");
    assertEquals(runDetail, runDetail(session), "run detail differs once archived");

    // And unfolding puts the rows back without changing a byte of the responses.
    folder.unfold(updateId);
    assertEquals(beforeFold, pageThrough(session, updateId), "pages differ once unfolded");
  }

  @Test
  void inProgressRunDetailIncludesBackendDerivedChanges() {
    Cookie session = ownerSession();
    UUID stackId = createStack();
    seedUpdate(stackId, StackUpdate.Status.IN_PROGRESS);

    Response response =
        given()
            .cookie(session.getName(), session.getValue())
            .when()
            .get("/api/pulumi/stacks/%s/%s/%s/updates/1".formatted(ORG, PROJECT, STACK));

    response.then().statusCode(200);
    int derivedChanges =
        response.jsonPath().getList("changes.changed").size()
            + response.jsonPath().getList("changes.unchanged").size()
            + response.jsonPath().getList("changes.activity").size()
            + response.jsonPath().getList("changes.outputs").size();
    assertTrue(derivedChanges > 0, "live responses must carry the canonical backend model");
  }

  /** Walks the endpoint the way the console does, collecting each raw response body. */
  private List<String> pageThrough(Cookie session, UUID updateId) {
    List<String> bodies = new ArrayList<>();
    Integer after = null;
    do {
      var request = given().cookie(session.getName(), session.getValue());
      if (after != null) {
        request = request.queryParam("after", after);
      }
      Response response =
          request
              .when()
              .get(
                  "/api/pulumi/stacks/%s/%s/%s/updates/%s/events"
                      .formatted(ORG, PROJECT, STACK, updateId));
      response.then().statusCode(200);
      bodies.add(response.asString());
      after = response.jsonPath().get("nextAfter");
    } while (after != null);
    return bodies;
  }

  private String runDetail(Cookie session) {
    return given()
        .cookie(session.getName(), session.getValue())
        .when()
        .get("/api/pulumi/stacks/%s/%s/%s/updates/1".formatted(ORG, PROJECT, STACK))
        .then()
        .statusCode(200)
        .body("summary", notNullValue())
        .extract()
        .asString();
  }

  private UUID createStack() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Organization org = Organization.<Organization>find("name", ORG).firstResult();
              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
              return Stack.<Stack>find("orgId = ?1 and name = ?2", org.id, STACK).firstResult().id;
            });
  }

  private UUID seedUpdate(UUID stackId) {
    return seedUpdate(stackId, StackUpdate.Status.SUCCEEDED);
  }

  private UUID seedUpdate(UUID stackId, StackUpdate.Status status) {
    List<JsonNode> corpus = EventArchiveFixture.recordedEvents();
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              StackUpdate update = new StackUpdate();
              update.stackId = stackId;
              update.kind = StackUpdate.Kind.UPDATE;
              update.status = status;
              update.version = 1;
              update.startedAt = LONG_AGO;
              update.endedAt = LONG_AGO;
              update.persistAndFlush();
              EventArchiveFixture.persistEvents(stackId, update.id, corpus, EVENT_COUNT);
              return update.id;
            });
  }

  private static Cookie ownerSession() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = ORG;
              org.persistAndFlush();
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
              Organization org = Organization.<Organization>find("name", ORG).firstResult();
              OrgMember membership = new OrgMember();
              membership.orgId = org.id;
              membership.userId = user.id;
              membership.role = OrgMember.Role.owner;
              membership.persistAndFlush();
            });
    return session;
  }
}
