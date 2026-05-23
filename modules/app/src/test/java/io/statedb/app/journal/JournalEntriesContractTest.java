package io.statedb.app.journal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.persistence.stack.StackCreator;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JournalEntriesContractTest {

  private static final String ORG = "journal-ct-org";
  private static final String RAW_TOKEN = "ct-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject EntityManager em;
  @Inject StackCreator stackCreator;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = "Journal CT";
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:test";
              user.sub = "journal-ct";
              user.email = "ct@example.com";
              user.displayName = "CT Tester";
              user.persistAndFlush();

              OrgMember m = new OrgMember();
              m.orgId = org.id;
              m.userId = user.id;
              m.role = OrgMember.Role.owner;
              m.persistAndFlush();

              ApiToken t = new ApiToken();
              t.tokenHash = sha256Hex(RAW_TOKEN);
              t.userId = user.id;
              t.orgId = org.id;
              t.kind = ApiToken.Kind.service_account;
              t.description = "ct";
              t.persistAndFlush();

              stackCreator.create(org.id, ORG, PROJECT, STACK, Map.of());
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:test")
                  .setParameter(2, "journal-ct")
                  .executeUpdate();
            });
  }

  @Test
  void gzippedPatchPersistsEntries() throws Exception {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    byte[] body =
        gzip(
            MAPPER.writeValueAsBytes(
                Map.of(
                    "entries",
                    List.of(
                        Map.of("kind", 6, "operationID", 0, "sequenceID", 1, "version", 1),
                        Map.of(
                            "kind",
                            0,
                            "operationID",
                            1,
                            "sequenceID",
                            2,
                            "version",
                            1,
                            "operation",
                            Map.of(
                                "resource",
                                Map.of("custom", false, "urn", "urn:x", "type", "t:x"),
                                "type",
                                "creating"))))));

    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(body)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(200);

    UUID upd = UUID.fromString(updateId);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long count =
                  ((Number)
                          em.createNativeQuery(
                                  "SELECT count(*) FROM journal_entries WHERE update_id = ?1")
                              .setParameter(1, upd)
                              .getSingleResult())
                      .longValue();
              assertEquals(2L, count);
            });
  }

  @Test
  void duplicateWireSequenceReturns409() throws Exception {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    byte[] firstBody =
        gzip(
            MAPPER.writeValueAsBytes(
                Map.of(
                    "entries",
                    List.of(Map.of("kind", 1, "operationID", 1, "sequenceID", 1, "version", 1)))));
    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(firstBody)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(200);

    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(firstBody)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(409);
  }

  @Test
  void invalidKindReturns400() throws Exception {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    byte[] body =
        gzip(
            MAPPER.writeValueAsBytes(
                Map.of(
                    "entries",
                    List.of(Map.of("kind", 99, "operationID", 0, "sequenceID", 1, "version", 1)))));

    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(body)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(400);
  }

  @Test
  void checkpointEndpointsReturn501() {
    String updateId = startPreview();
    String token = acquireLock(updateId);

    for (String leaf : List.of("checkpoint", "checkpointdelta", "checkpointverbatim")) {
      given()
          .header("Authorization", "update-token " + token)
          .contentType("application/json")
          .body("{}")
          .when()
          .patch(
              "/pulumi/api/stacks/"
                  + ORG
                  + "/"
                  + PROJECT
                  + "/"
                  + STACK
                  + "/update/"
                  + updateId
                  + "/"
                  + leaf)
          .then()
          .statusCode(501);
    }
  }

  @Test
  void malformedGzipReturns400NotSilent200() {
    // Regression: Vert.x's auto-decompression silently produces a null body for malformed gzip.
    // Without an explicit null-body check the handler would return 200 with {} and the CLI would
    // proceed as if the corrupt PATCH succeeded.
    String updateId = startPreview();
    String token = acquireLock(updateId);
    byte[] notGzip = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
    given()
        .header("Authorization", "update-token " + token)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(notGzip)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(400);
  }

  @Test
  void missingLeaseTokenReturns401() throws Exception {
    String updateId = startPreview();
    acquireLock(updateId);

    byte[] body =
        gzip(
            MAPPER.writeValueAsBytes(
                Map.of(
                    "entries",
                    List.of(Map.of("kind", 6, "operationID", 0, "sequenceID", 1, "version", 1)))));

    given()
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(body)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(401);
  }

  private String journalPath(String updateId) {
    return "/pulumi/api/stacks/"
        + ORG
        + "/"
        + PROJECT
        + "/"
        + STACK
        + "/update/"
        + updateId
        + "/journalentries";
  }

  private String startPreview() {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", PROJECT, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/preview")
        .then()
        .statusCode(200)
        .extract()
        .path("updateID");
  }

  private String acquireLock(String updateId) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("journalVersion", 1))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update/" + updateId)
        .then()
        .statusCode(200)
        .extract()
        .path("token");
  }

  private static byte[] gzip(byte[] data) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(baos)) {
      gz.write(data);
      gz.finish();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
