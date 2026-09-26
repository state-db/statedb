package app.statedb.vcs.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GitHubAppTokenSourceTest {

  @Test
  void graphqlBotLoginUsesAppSlugWithoutRestSuffix() {
    var registrations =
        new app.statedb.persistence.vcs.VcsAppRegistry() {
          @Override
          public java.util.Optional<AppStatus> status(String host) {
            return java.util.Optional.of(
                new AppStatus(
                    "github.com",
                    12345,
                    "state-db",
                    "state-db",
                    "https://github.com/apps/state-db",
                    1));
          }
        };
    var source =
        new GitHubAppTokenSource(
            new ObjectMapper(), java.net.URI.create("https://api.github.com"), registrations);
    assertEquals("state-db", source.graphqlBotLogin());
  }

  @Test
  void signsShortLivedAppJwtWithConfiguredRsaKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
    Instant now = Instant.parse("2026-09-21T20:00:00Z");
    ObjectMapper json = new ObjectMapper();
    String[] parts = GitHubAppJwt.sign(12345, pem, now).split("\\.");
    assertEquals(3, parts.length);
    JsonNode header = json.readTree(decode(parts[0]));
    JsonNode payload = json.readTree(decode(parts[1]));
    assertEquals("RS256", header.path("alg").asText());
    assertEquals("12345", payload.path("iss").asText());
    assertEquals(now.minusSeconds(60).getEpochSecond(), payload.path("iat").asLong());
    assertEquals(now.plusSeconds(540).getEpochSecond(), payload.path("exp").asLong());

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keyPair.getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
  }

  private static byte[] decode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }
}
