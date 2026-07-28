package io.statedb.app.stack;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.journal.JournalEntry;
import io.statedb.persistence.stack.StackCreator;
import io.statedb.persistence.stack.StackImporter;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StackExportContractTest {

  private static final String ORG = "export-org";
  private static final String OTHER_ORG = "export-other-org";
  private static final String PROJECT = "proj";
  private static final String STACK = "dev";
  private static final String RAW_TOKEN = "export-token";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject StackCreator stackCreator;
  @Inject StackImporter stackImporter;
  @Inject EntityManager em;

  private UUID stackId;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = org(ORG);
              Organization other = org(OTHER_ORG);

              User user = new User();
              user.iss = "local:test";
              user.sub = "export";
              user.email = "export@example.com";
              user.displayName = "Export Tester";
              user.persistAndFlush();

              OrgMember member = new OrgMember();
              member.orgId = org.id;
              member.userId = user.id;
              member.role = OrgMember.Role.owner;
              member.persistAndFlush();

              ApiToken token = new ApiToken();
              token.tokenHash = sha256Hex(RAW_TOKEN);
              token.userId = user.id;
              token.orgId = org.id;
              token.kind = ApiToken.Kind.service_account;
              token.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
              token.description = "export";
              token.persistAndFlush();

              stackId = stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of()).stackId();
              stackCreator.create(other.id, OTHER_ORG, PROJECT, STACK, Map.of());
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
                  .setParameter(1, ORG)
                  .setParameter(2, OTHER_ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "export")
                  .executeUpdate();
            });
  }

  @Test
  void emptyStackExportsUntypedDeploymentShape() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK))
        .then()
        .statusCode(200)
        .body("version", equalTo(3))
        .body("features", nullValue())
        .body("deployment.resources", hasSize(0))
        .body("deployment.manifest.version", equalTo("v3.242.0"));
  }

  @Test
  void unknownStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, "missing"))
        .then()
        .statusCode(404);
  }

  @Test
  void crossOrgReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(OTHER_ORG, PROJECT, STACK))
        .then()
        .statusCode(404);
  }

  @Test
  void noAuthReturns401() {
    given().when().get(path(ORG, PROJECT, STACK)).then().statusCode(401);
  }

  // pulumi stack export --version N — the CLI appends /N to the export path.

  @Test
  void versionedExportReturnsStateAsOfThatVersion() {
    seedTwoAppliedVersions();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/1")
        .then()
        .statusCode(200)
        .body("deployment.resources", hasSize(1))
        .body("deployment.resources[0].urn", equalTo(urn("a")));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/2")
        .then()
        .statusCode(200)
        .body("deployment.resources", hasSize(2));
  }

  @Test
  void versionedExportUnknownVersionReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/7")
        .then()
        .statusCode(404);
  }

  @Test
  void versionedExportInProgressUpdateReturns404() {
    QuarkusTransaction.requiringNew()
        .run(() -> appliedUpdate(1, StackUpdate.Status.IN_PROGRESS, 0L, null));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/1")
        .then()
        .statusCode(404);
  }

  @Test
  void versionedExportDryRunVersionZeroReturns404() {
    seedTwoAppliedVersions();

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/0")
        .then()
        .statusCode(404);
  }

  // Import records an applied version at the current journal head without advancing it, so its
  // snapshot shares version 2's boundary. Each version must still export its own state.
  @Test
  void versionedExportDistinguishesImportSharingJournalBoundary() throws Exception {
    seedTwoAppliedVersions();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              StackState state = StackState.<StackState>find("stackId", stackId).firstResult();
              state.journalSeq = 4L;
            });
    stackImporter.importDeployment(
        stackId,
        null,
        null,
        MAPPER.readTree(
            "{\"resources\":[{\"urn\":\""
                + urn("imported")
                + "\",\"type\":\"pkg:index:imported\",\"custom\":true}]}"));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/3")
        .then()
        .statusCode(200)
        .body("deployment.resources", hasSize(1))
        .body("deployment.resources[0].urn", equalTo(urn("imported")));

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .when()
        .get(path(ORG, PROJECT, STACK) + "/2")
        .then()
        .statusCode(200)
        .body("deployment.resources", hasSize(2))
        .body("deployment.resources.urn", containsInAnyOrder(urn("a"), urn("b")));
  }

  @Test
  void updateTokenDoesNotAuthorizeExport() {
    given()
        .header("Authorization", "update-token whatever")
        .when()
        .get(path(ORG, PROJECT, STACK))
        .then()
        .statusCode(401);
  }

  // Two applied versions with production boundary semantics: end_journal_seq is one past the
  // update's last journal entry (the gap JournalAppender's counter advance leaves).
  private void seedTwoAppliedVersions() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              UUID first = appliedUpdate(1, StackUpdate.Status.SUCCEEDED, 0L, 2L);
              successEntry(first, 1L, "a");
              UUID second = appliedUpdate(2, StackUpdate.Status.SUCCEEDED, 2L, 4L);
              successEntry(second, 3L, "b");
            });
  }

  private UUID appliedUpdate(int version, StackUpdate.Status status, Long startSeq, Long endSeq) {
    StackUpdate update = new StackUpdate();
    update.stackId = stackId;
    update.version = version;
    update.kind = StackUpdate.Kind.UPDATE;
    update.status = status;
    update.startJournalSeq = startSeq;
    update.endJournalSeq = endSeq;
    update.startedAt = Instant.now();
    update.persistAndFlush();
    return update.id;
  }

  private void successEntry(UUID updateId, long sequenceId, String name) {
    JournalEntry entry = new JournalEntry();
    entry.stackId = stackId;
    entry.updateId = updateId;
    entry.sequenceId = sequenceId;
    entry.wireSequenceId = sequenceId;
    entry.kind = 1;
    entry.operationId = 1L;
    try {
      entry.payload =
          MAPPER.readTree(
              "{\"state\":{\"urn\":\""
                  + urn(name)
                  + "\",\"type\":\"pkg:index:"
                  + name
                  + "\",\"custom\":true}}");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    entry.persistAndFlush();
  }

  private static String urn(String name) {
    return "urn:pulumi:dev::proj::pkg:index:" + name + "::" + name;
  }

  private Organization org(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    org.persistAndFlush();
    return org;
  }

  private static String path(String org, String project, String stack) {
    return "/pulumi/api/stacks/" + org + "/" + project + "/" + stack + "/export";
  }

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
