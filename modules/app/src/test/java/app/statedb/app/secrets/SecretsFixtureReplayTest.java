package app.statedb.app.secrets;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.app.DatabaseTest;
import app.statedb.app.contract.FixtureReplay;
import app.statedb.testsupport.RepoRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Redaction-aware fixture replay for the secrets endpoints. The recorder writes {@code "[secret]"}
 * placeholders in place of every plaintext/ciphertext payload, so we cannot replay bytes verbatim
 * and assert byte-equal responses. Instead:
 *
 * <ul>
 *   <li>{@code /encrypt} fixtures are sent as recorded (any string is a valid plaintext). We assert
 *       the route returns 200 with a non-empty {@code ciphertext} field.
 *   <li>{@code /batch-decrypt} fixtures have their redacted ciphertexts rewritten to a real one
 *       produced by calling {@code /encrypt} first under the same stack. The expected {@code
 *       plaintexts} map key is rewritten to match. Body is gzipped when the fixture was.
 * </ul>
 *
 * This still asserts route mounting, status, request/response shape, gzip request decoding, and
 * end-to-end crypto wiring — without pretending {@code [secret]} is valid base64.
 */
@QuarkusTest
class SecretsFixtureReplayTest extends DatabaseTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FIXTURE_ROOT = "tools/pulumi/recorder/fixtures/by-command";
  private static final String REDACTED = "[secret]";
  private static final String STACK_PATH = "/pulumi/api/stacks/testorg/recorder-test/dev";

  @BeforeEach
  void seed() {
    FixtureReplay.seedIdentityWithStack(em);
  }

  @Test
  void replaysAllSecretsFixtures() throws IOException {
    List<Path> fixtures = secretsFixtures();
    assertFalse(fixtures.isEmpty(), "no secrets fixtures discovered under " + FIXTURE_ROOT);

    int encryptCount = 0;
    int batchDecryptCount = 0;
    for (Path fixture : fixtures) {
      JsonNode exchange = JSON.readTree(fixture.toFile());
      String path = exchange.required("path").asText();
      if (path.endsWith("/encrypt")) {
        replayEncrypt(fixture, exchange);
        encryptCount++;
      } else if (path.endsWith("/batch-decrypt")) {
        replayBatchDecrypt(fixture, exchange);
        batchDecryptCount++;
      }
    }
    assertTrue(encryptCount > 0, "expected at least one /encrypt fixture");
    assertTrue(batchDecryptCount > 0, "expected at least one /batch-decrypt fixture");
  }

  private void replayEncrypt(Path fixture, JsonNode exchange) throws IOException {
    JsonNode body = exchange.required("body");
    Response response =
        request(exchange)
            .contentType("application/json")
            .body(JSON.writeValueAsBytes(body))
            .when()
            .post(STACK_PATH + "/encrypt");

    assertEquals(
        exchange.required("status_code").asInt(), response.statusCode(), () -> fixture + " status");
    String ciphertext = response.jsonPath().getString("ciphertext");
    assertNotNull(ciphertext, () -> fixture + " response ciphertext");
    assertFalse(ciphertext.isBlank(), () -> fixture + " response ciphertext blank");
  }

  private void replayBatchDecrypt(Path fixture, JsonNode exchange) throws IOException {
    // Produce a real ciphertext for the same stack to substitute in.
    String plaintext = "fixture-plaintext-" + fixture.getFileName();
    String realCiphertext = encryptForReplay(plaintext);

    ObjectNode body = (ObjectNode) exchange.required("body").deepCopy();
    ArrayNode ciphertexts = (ArrayNode) body.required("ciphertexts");
    int len = ciphertexts.size();
    assertTrue(len > 0, () -> fixture + " has empty ciphertexts list");
    ArrayNode rewritten = JSON.createArrayNode();
    for (int i = 0; i < len; i++) {
      // The recorder redacts every entry to the same "[secret]" placeholder, so we cannot
      // reconstruct distinct plaintexts. Substituting one real ciphertext exercises the dedupe
      // path the live CLI relies on (response is a map keyed by ciphertext).
      final int idx = i;
      String entry = ciphertexts.get(i).asText();
      assertEquals(REDACTED, entry, () -> fixture + " entry " + idx + " expected to be redacted");
      rewritten.add(realCiphertext);
    }
    body.set("ciphertexts", rewritten);

    byte[] jsonBytes = JSON.writeValueAsBytes(body);
    RequestSpecification req = request(exchange).contentType("application/json");
    final byte[] payload;
    if (exchange.path("body_gzip").asBoolean(false)) {
      req.header("Content-Encoding", "gzip");
      payload = gzip(jsonBytes);
    } else {
      payload = jsonBytes;
    }
    req.body(payload);

    Response response = req.when().post(STACK_PATH + "/batch-decrypt");
    assertEquals(
        exchange.required("status_code").asInt(), response.statusCode(), () -> fixture + " status");

    JsonPath json = response.jsonPath();
    Map<String, String> plaintexts = json.getMap("plaintexts");
    assertNotNull(plaintexts, () -> fixture + " response missing plaintexts");
    assertEquals(1, plaintexts.size(), () -> fixture + " expected single dedup'd entry");
    assertEquals(plaintext, plaintexts.get(realCiphertext), () -> fixture + " roundtripped value");
  }

  private String encryptForReplay(String plaintext) throws IOException {
    byte[] body = JSON.writeValueAsBytes(Map.of("plaintext", plaintext));
    return given()
        .header("Authorization", "token " + FixtureReplay.DEFAULT_TOKEN)
        .contentType("application/json")
        .body(body)
        .when()
        .post(STACK_PATH + "/encrypt")
        .then()
        .statusCode(200)
        .extract()
        .path("ciphertext");
  }

  private RequestSpecification request(JsonNode exchange) {
    RequestSpecification req = given();
    Iterator<Map.Entry<String, JsonNode>> fields = exchange.path("headers").fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String name = entry.getKey();
      if ("Content-Length".equalsIgnoreCase(name)
          || "Accept-Encoding".equalsIgnoreCase(name)
          || "Content-Encoding".equalsIgnoreCase(name)
          || "Content-Type".equalsIgnoreCase(name)) {
        // Length is computed by the client. Accept-Encoding and Content-Encoding are managed by
        // this harness explicitly. Content-Type is set on the spec to keep REST-assured happy.
        continue;
      }
      if ("Authorization".equalsIgnoreCase(name)) {
        req.header(name, "token " + FixtureReplay.DEFAULT_TOKEN);
      } else {
        req.header(name, entry.getValue().asText());
      }
    }
    return req;
  }

  private static byte[] gzip(byte[] data) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
      gz.finish();
      return out.toByteArray();
    }
  }

  private static List<Path> secretsFixtures() {
    Path root = RepoRoot.get().resolve(FIXTURE_ROOT);
    List<Path> out = new ArrayList<>();
    try (var stream = Files.walk(root)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(
              p -> {
                String name = p.getFileName().toString();
                return name.contains("_encrypt") || name.contains("_batch-decrypt");
              })
          .sorted()
          .forEach(out::add);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to walk fixtures under " + root, e);
    }
    return out;
  }
}
