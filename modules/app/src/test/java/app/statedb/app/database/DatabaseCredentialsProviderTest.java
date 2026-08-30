package app.statedb.app.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

class DatabaseCredentialsProviderTest {

  @Test
  void returnsConfiguredPasswordInPasswordMode() {
    DatabaseCredentialsProvider provider =
        provider("password", Optional.of("secret"), Optional.empty(), Optional.empty());

    assertEquals(
        Map.of("user", "statedb", "password", "secret"),
        provider.getCredentials(DatabaseCredentialsProvider.PROVIDER_KEY));
  }

  @Test
  void generatesSignedRdsTokenInIamMode() {
    DatabaseCredentialsProvider provider =
        provider(
            "aws-iam",
            Optional.empty(),
            Optional.of("db.example.us-east-1.rds.amazonaws.com"),
            Optional.of("us-east-1"));

    String token =
        provider.getCredentials(DatabaseCredentialsProvider.PROVIDER_KEY).get("password");

    assertTrue(token.startsWith("db.example.us-east-1.rds.amazonaws.com:5432"));
    assertTrue(token.contains("Action=connect"));
    assertTrue(token.contains("DBUser=statedb"));
    assertTrue(token.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
    assertTrue(token.contains("X-Amz-Credential=access-key"));
    assertTrue(token.contains("X-Amz-Signature="));
  }

  @Test
  void generatesTokenFromEcsContainerCredentialsEndpoint() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/credentials",
        exchange -> {
          byte[] response =
              """
              {
                "AccessKeyId": "container-access-key",
                "SecretAccessKey": "container-secret-key",
                "Token": "container-session-token",
                "Expiration": "2099-01-01T00:00:00Z"
              }
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    try {
      URI endpoint =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/credentials");
      EcsTaskCredentialsProvider credentials =
          new EcsTaskCredentialsProvider(
              HttpClient.newHttpClient(),
              new ObjectMapper(),
              () -> "/credentials",
              Clock.systemUTC(),
              endpoint);
      RdsIamAuthenticationTokenGenerator generator =
          new RdsIamAuthenticationTokenGenerator(credentials::resolveCredentials);

      String token =
          generator.generate(
              "db.example.us-east-1.rds.amazonaws.com", 5432, "statedb", "us-east-1");

      assertTrue(token.contains("X-Amz-Credential=container-access-key"));
      assertTrue(token.contains("X-Amz-Security-Token=container-session-token"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsMissingModeSpecificConfiguration() {
    DatabaseCredentialsProvider passwordProvider =
        provider("password", Optional.empty(), Optional.empty(), Optional.empty());
    DatabaseCredentialsProvider iamProvider =
        provider("aws-iam", Optional.empty(), Optional.empty(), Optional.of("us-east-1"));

    assertEquals(
        "STATEDB_DB_PASSWORD is required for database access",
        assertThrows(
                IllegalStateException.class,
                () -> passwordProvider.getCredentials(DatabaseCredentialsProvider.PROVIDER_KEY))
            .getMessage());
    assertEquals(
        "STATEDB_DB_HOST is required for database access",
        assertThrows(
                IllegalStateException.class,
                () -> iamProvider.getCredentials(DatabaseCredentialsProvider.PROVIDER_KEY))
            .getMessage());
  }

  private static DatabaseCredentialsProvider provider(
      String authentication,
      Optional<String> password,
      Optional<String> host,
      Optional<String> region) {
    RdsIamAuthenticationTokenGenerator generator =
        new RdsIamAuthenticationTokenGenerator(
            () -> AwsCredentialsIdentity.create("access-key", "secret"));
    return new DatabaseCredentialsProvider(
        generator, authentication, Optional.of("statedb"), password, host, 5432, region);
  }
}
