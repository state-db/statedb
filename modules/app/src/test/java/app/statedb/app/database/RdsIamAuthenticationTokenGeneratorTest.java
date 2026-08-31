package app.statedb.app.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RdsIamAuthenticationTokenGeneratorTest {

  private static final Instant SIGNING_TIME = Instant.parse("2015-08-30T12:36:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(SIGNING_TIME, ZoneOffset.UTC);
  private static final String ACCESS_KEY = "AKIDEXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

  @Test
  void matchesAwsSdkGoldenVector() {
    RdsIamAuthenticationTokenGenerator generator = generator(basicCredentials());

    String token = generator.generate("database.example.com", 5432, "db-user", "us-east-1");

    // Independently generated with AWS SDK for Java v2.54.7's AwsV4HttpSigner using the same
    // fixed clock. The SDK emits query parameters in a different display order, but its canonical
    // request produces this signature.
    assertEquals(
        "database.example.com:5432/?Action=connect&DBUser=db-user"
            + "&X-Amz-Algorithm=AWS4-HMAC-SHA256"
            + "&X-Amz-Credential=AKIDEXAMPLE%2F20150830%2Fus-east-1%2Frds-db%2Faws4_request"
            + "&X-Amz-Date=20150830T123600Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host"
            + "&X-Amz-Signature=c07da4600988c5d1be3f6dd86704bed69ddaa18f2f2a447598cb6d41f4477876",
        token);
  }

  @Test
  void signsTemporaryCredentialsIncludingTheSessionToken() {
    RdsIamAuthenticationTokenGenerator generator =
        generator(new AwsCredentials(ACCESS_KEY, SECRET_KEY, "token+/= value"));

    String token = generator.generate("database.example.com", 5432, "db-user", "us-east-1");

    assertTrue(token.contains("X-Amz-Security-Token=token%2B%2F%3D%20value"));
    assertTrue(
        token.endsWith(
            "X-Amz-Signature=eff78bb3ae427b958acba2517a15a984adf41aa2e201eda94c8bde46cabba591"));
  }

  @Test
  void percentEncodesUtf8AndSortsTheCanonicalQuery() {
    RdsIamAuthenticationTokenGenerator generator =
        generator(AwsCredentials.basic("AKID/+", SECRET_KEY));

    String token = generator.generate("database.example.com", 3306, "name +/%~é", "us-east-1");

    String query = token.substring(token.indexOf('?') + 1, token.indexOf("&X-Amz-Signature="));
    assertEquals(
        List.of(
            "Action=connect",
            "DBUser=name%20%2B%2F%25~%C3%A9",
            "X-Amz-Algorithm=AWS4-HMAC-SHA256",
            "X-Amz-Credential=AKID%2F%2B%2F20150830%2Fus-east-1%2Frds-db%2Faws4_request",
            "X-Amz-Date=20150830T123600Z",
            "X-Amz-Expires=900",
            "X-Amz-SignedHeaders=host"),
        List.of(query.split("&")));
  }

  @Test
  void includesThePortInTheLowercaseHostAndSignature() {
    RdsIamAuthenticationTokenGenerator generator = generator(basicCredentials());

    String port5432 = generator.generate("DATABASE.EXAMPLE.COM", 5432, "db-user", "us-east-1");
    String port5433 = generator.generate("database.example.com", 5433, "db-user", "us-east-1");

    assertTrue(port5432.startsWith("database.example.com:5432/?"));
    assertTrue(port5433.startsWith("database.example.com:5433/?"));
    assertTrue(
        port5432.endsWith(
            "X-Amz-Signature=c07da4600988c5d1be3f6dd86704bed69ddaa18f2f2a447598cb6d41f4477876"));
    assertTrue(
        port5433.endsWith(
            "X-Amz-Signature=5cdc34e36c54a0c9dd78242922d5620cc7ae323b862c311831813c7ec191c5af"));
  }

  @Test
  void rejectsInvalidRequestValuesBeforeResolvingCredentials() {
    int[] credentialResolutions = {0};
    RdsIamAuthenticationTokenGenerator generator =
        new RdsIamAuthenticationTokenGenerator(
            () -> {
              credentialResolutions[0]++;
              return basicCredentials();
            },
            FIXED_CLOCK);

    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("https://database.example.com", 5432, "db-user", "us-east-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("-database.example.com", 5432, "db-user", "us-east-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("database.example.com", 0, "db-user", "us-east-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("database.example.com", 65_536, "db-user", "us-east-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("database.example.com", 5432, " ", "us-east-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("database.example.com", 5432, "db\nuser", "us-east-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> generator.generate("database.example.com", 5432, "db-user", "US_EAST_1"));
    assertEquals(0, credentialResolutions[0]);
  }

  @Test
  void rejectsInvalidCredentialValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> generateValidToken(generator(new AwsCredentials(" ", SECRET_KEY, null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> generateValidToken(generator(new AwsCredentials(ACCESS_KEY, " ", null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> generateValidToken(generator(new AwsCredentials(ACCESS_KEY, SECRET_KEY, " "))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RdsIamAuthenticationTokenGenerator(() -> null, FIXED_CLOCK)
                .generate("database.example.com", 5432, "db-user", "us-east-1"));
  }

  @Test
  void propagatesCredentialProviderFailures() {
    IllegalStateException failure = new IllegalStateException("credential endpoint failed");
    RdsIamAuthenticationTokenGenerator generator =
        new RdsIamAuthenticationTokenGenerator(
            (Supplier<AwsCredentials>)
                () -> {
                  throw failure;
                },
            FIXED_CLOCK);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> generator.generate("database.example.com", 5432, "db-user", "us-east-1"));

    assertSame(failure, thrown);
  }

  private static RdsIamAuthenticationTokenGenerator generator(AwsCredentials credentials) {
    return new RdsIamAuthenticationTokenGenerator(() -> credentials, FIXED_CLOCK);
  }

  private static AwsCredentials basicCredentials() {
    return AwsCredentials.basic(ACCESS_KEY, SECRET_KEY);
  }

  private static String generateValidToken(RdsIamAuthenticationTokenGenerator generator) {
    return generator.generate("database.example.com", 5432, "db-user", "us-east-1");
  }
}
