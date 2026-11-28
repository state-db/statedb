package app.statedb.app.secrets;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import app.statedb.app.DatabaseTest;
import app.statedb.app.TestDb;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiTokenHasher;
import app.statedb.persistence.identity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@code /encrypt}, {@code /decrypt}, {@code /batch-decrypt}. Uses two stacks
 * under the same token to assert cross-stack isolation via {@code stack.id} AAD.
 */
@QuarkusTest
class SecretsResourceTest extends DatabaseTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ORG_NAME = "secrets-org";
  private static final String RAW_TOKEN = "secrets-token";
  // Account-scoped oidc token (org_id null) for the same user — the kind `pulumi login` mints.
  private static final String RAW_TOKEN_SSO = "secrets-sso-token";
  // Account-scoped oidc token for a user who is NOT a member of the org.
  private static final String RAW_TOKEN_OUTSIDER = "secrets-outsider-token";
  private static final String OUTSIDER_SUB = "secrets-outsider";
  private static final String PROJECT = "secrets-proj";

  @Inject ApiTokenHasher tokenHasher;

  @BeforeEach
  void seed() {
    TestDb.SeededOwner owner = db.seedOwner(ORG_NAME, RAW_TOKEN);
    db.seedStack(ORG_NAME, PROJECT, "alpha");
    db.seedStack(ORG_NAME, PROJECT, "beta");

    requiringNew()
        .run(
            () -> {
              ApiToken sso = new ApiToken();
              sso.tokenHash = tokenHasher.hash(RAW_TOKEN_SSO);
              sso.userId = owner.userId();
              sso.orgId = null; // account-scoped: role resolved per request from org_members
              sso.kind = ApiToken.Kind.oidc;
              sso.expiresAt = Instant.now().plusSeconds(3600);
              sso.description = "secrets-sso-test";
              sso.persistAndFlush();

              // A valid account-scoped token whose user has NO membership in the org.
              User outsider = new User();
              outsider.iss = TestDb.TEST_ISS;
              outsider.sub = OUTSIDER_SUB;
              outsider.email = "outsider@example.com";
              outsider.persistAndFlush();

              ApiToken outsiderToken = new ApiToken();
              outsiderToken.tokenHash = tokenHasher.hash(RAW_TOKEN_OUTSIDER);
              outsiderToken.userId = outsider.id;
              outsiderToken.orgId = null;
              outsiderToken.kind = ApiToken.Kind.oidc;
              outsiderToken.expiresAt = Instant.now().plusSeconds(3600);
              outsiderToken.description = "secrets-outsider-test";
              outsiderToken.persistAndFlush();
            });
  }

  @Test
  void encryptThenDecryptRoundTrips() {
    String ciphertext =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .contentType("application/json")
            .body(Map.of("plaintext", "supersecret"))
            .when()
            .post(stackPath("alpha") + "/encrypt")
            .then()
            .statusCode(200)
            .body("ciphertext", not(nullValue()))
            .extract()
            .path("ciphertext");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertext", ciphertext))
        .when()
        .post(stackPath("alpha") + "/decrypt")
        .then()
        .statusCode(200)
        .body("plaintext", equalTo("supersecret"));
  }

  @Test
  void accountScopedOidcTokenCanEncryptAndDecrypt() {
    // Regression: an SSO-minted oidc token has org_id == null. The @Authorize interceptor gates the
    // request via the user's org membership; aadFor must not re-compare against the (null) token
    // org
    // and 404 a valid request.
    String ciphertext =
        given()
            .header("Authorization", "token " + RAW_TOKEN_SSO)
            .contentType("application/json")
            .body(Map.of("plaintext", "sso-secret"))
            .when()
            .post(stackPath("alpha") + "/encrypt")
            .then()
            .statusCode(200)
            .extract()
            .path("ciphertext");

    given()
        .header("Authorization", "token " + RAW_TOKEN_SSO)
        .contentType("application/json")
        .body(Map.of("ciphertext", ciphertext))
        .when()
        .post(stackPath("alpha") + "/decrypt")
        .then()
        .statusCode(200)
        .body("plaintext", equalTo("sso-secret"));
  }

  @Test
  void accountScopedTokenForNonMemberIsDenied() {
    // Proves where org access is enforced for account-scoped tokens: not from the token (org_id is
    // null) but from org_members, via PrincipalRoleResolver in the @Authorize loader. A valid token
    // whose user is not a member of the org gets 404 — no membership, no role, no access.
    given()
        .header("Authorization", "token " + RAW_TOKEN_OUTSIDER)
        .contentType("application/json")
        .body(Map.of("plaintext", "x"))
        .when()
        .post(stackPath("alpha") + "/encrypt")
        .then()
        .statusCode(404);
  }

  @Test
  void crossStackDecryptReturns400() {
    String ciphertext = encryptUnder("alpha", "alpha-secret");

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertext", ciphertext))
        .when()
        .post(stackPath("beta") + "/decrypt")
        .then()
        .statusCode(400)
        .body("message", equalTo("invalid ciphertext"));
  }

  @Test
  void tamperedCiphertextReturns400() {
    String ciphertext = encryptUnder("alpha", "x");
    // Replace the trailing byte to corrupt the GCM tag.
    String tampered = ciphertext.substring(0, ciphertext.length() - 2) + "AA";

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertext", tampered))
        .when()
        .post(stackPath("alpha") + "/decrypt")
        .then()
        .statusCode(400);
  }

  @Test
  void invalidBase64Returns400() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertext", "***not-base64***"))
        .when()
        .post(stackPath("alpha") + "/decrypt")
        .then()
        .statusCode(400);
  }

  @Test
  void batchDecryptDeduplicatesAndReturnsMap() {
    String c1 = encryptUnder("alpha", "first");
    String c2 = encryptUnder("alpha", "second");

    JsonPath response =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .contentType("application/json")
            .body(Map.of("ciphertexts", List.of(c1, c2, c1)))
            .when()
            .post(stackPath("alpha") + "/batch-decrypt")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    Map<String, String> plaintexts = response.getMap("plaintexts");
    org.hamcrest.MatcherAssert.assertThat(
        plaintexts, allOf(aMapWithSize(2), hasEntry(c1, "first"), hasEntry(c2, "second")));
  }

  @Test
  void batchDecryptRoundTripsCiphertextsEmbeddedInPulumiSecretValues() throws Exception {
    String passwordCiphertext = encryptUnder("alpha", "correct horse");
    String tokenCiphertext = encryptUnder("alpha", "battery staple");
    JsonNode stackState =
        JSON.readTree(
            """
            {
              "outputs": {
                "password": %s,
                "token": %s
              }
            }
            """
                .formatted(secretValue(passwordCiphertext), secretValue(tokenCiphertext)));

    String password =
        stackState.required("outputs").required("password").required("ciphertext").asText();
    String token = stackState.required("outputs").required("token").required("ciphertext").asText();
    JsonPath response =
        given()
            .header("Authorization", "token " + RAW_TOKEN)
            .contentType("application/json")
            .body(Map.of("ciphertexts", List.of(password, token)))
            .when()
            .post(stackPath("alpha") + "/batch-decrypt")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    Map<String, String> plaintexts = response.getMap("plaintexts");
    org.hamcrest.MatcherAssert.assertThat(
        plaintexts,
        allOf(
            aMapWithSize(2),
            hasEntry(passwordCiphertext, "correct horse"),
            hasEntry(tokenCiphertext, "battery staple")));
  }

  @Test
  void batchDecryptFailsWholeBatchOnAnyBadEntry() {
    String good = encryptUnder("alpha", "ok");
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("ciphertexts", List.of(good, "***not-base64***")))
        .when()
        .post(stackPath("alpha") + "/batch-decrypt")
        .then()
        .statusCode(400);
  }

  @Test
  void identicalPlaintextsProduceDistinctCiphertexts() {
    String a = encryptUnder("alpha", "same");
    String b = encryptUnder("alpha", "same");
    org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
  }

  @Test
  void missingStackReturns404() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("plaintext", "x"))
        .when()
        .post("/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/no-such/encrypt")
        .then()
        .statusCode(404);
  }

  @Test
  void batchDecryptAcceptsGzippedRequestBody() throws Exception {
    String c1 = encryptUnder("alpha", "first");
    String c2 = encryptUnder("alpha", "second");
    byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("ciphertexts", List.of(c1, c2, c1)));
    byte[] gzipped;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(body);
      gz.finish();
      gzipped = out.toByteArray();
    }

    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .header("Content-Encoding", "gzip")
        .contentType("application/json")
        .body(gzipped)
        .when()
        .post(stackPath("alpha") + "/batch-decrypt")
        .then()
        .statusCode(200)
        .body("plaintexts." + jsonKey(c1), equalTo("first"))
        .body("plaintexts." + jsonKey(c2), equalTo("second"));
  }

  private static String jsonKey(String raw) {
    return "\"" + raw + "\"";
  }

  @Test
  void encryptResponseHasOnlyCiphertextField() {
    given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("plaintext", "x"))
        .when()
        .post(stackPath("alpha") + "/encrypt")
        .then()
        .statusCode(200)
        .body("$", hasKey("ciphertext"));
  }

  private String encryptUnder(String stack, String plaintext) {
    return given()
        .header("Authorization", "token " + RAW_TOKEN)
        .contentType("application/json")
        .body(Map.of("plaintext", plaintext))
        .when()
        .post(stackPath(stack) + "/encrypt")
        .then()
        .statusCode(200)
        .extract()
        .path("ciphertext");
  }

  private static String stackPath(String stack) {
    return "/pulumi/api/stacks/" + ORG_NAME + "/" + PROJECT + "/" + stack;
  }

  private static String secretValue(String ciphertext) {
    return "{\"4dabf18193072939515e22adb298388d\":\"1b47061264138c4ac30d75fd1eb44270\","
        + "\"ciphertext\":\""
        + ciphertext
        + "\"}";
  }
}
