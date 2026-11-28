package app.statedb.app.journal;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.journal.JournalEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JournalEntriesContractTest extends DatabaseTest {

  private static final String ORG = "journal-ct-org";
  private static final String RAW_TOKEN = "ct-token";
  private static final String PROJECT = "recorder-test";
  private static final String STACK = "dev";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeEach
  void seed() {
    db.seedOwnerWithStack(ORG, RAW_TOKEN, PROJECT, STACK);
  }

  @Test
  void gzippedPatchPersistsEntries() throws Exception {
    String updateId = startUpdate();
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

    assertEquals(2L, JournalEntry.count("updateId", UUID.fromString(updateId)));
  }

  @Test
  void duplicateWireSequenceRetryReturns200() throws Exception {
    String updateId = startUpdate();
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
        .statusCode(200);

    assertEquals(1L, JournalEntry.count("updateId", UUID.fromString(updateId)));
  }

  @Test
  void duplicateWireSequenceWithDifferentContentsReturnsPulumiConflict() throws Exception {
    String updateId = startUpdate();
    String token = acquireLock(updateId);

    byte[] firstBody =
        gzip(
            MAPPER.writeValueAsBytes(
                Map.of(
                    "entries",
                    List.of(Map.of("kind", 1, "operationID", 1, "sequenceID", 1, "version", 1)))));
    byte[] conflictingBody =
        gzip(
            MAPPER.writeValueAsBytes(
                Map.of(
                    "entries",
                    List.of(Map.of("kind", 1, "operationID", 2, "sequenceID", 1, "version", 1)))));

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
        .body(conflictingBody)
        .when()
        .patch(journalPath(updateId))
        .then()
        .statusCode(409)
        .contentType(JSON)
        .body("code", equalTo(409))
        .body("message", containsString("duplicate wire sequenceID with different contents"));
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

  private String startUpdate() {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("name", PROJECT, "runtime", "yaml"))
        .when()
        .post("/pulumi/api/stacks/" + ORG + "/" + PROJECT + "/" + STACK + "/update")
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
}
