package app.statedb.app.database;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Generates SigV4 PostgreSQL passwords from the ECS task role's container credentials. */
@ApplicationScoped
public class RdsIamAuthenticationTokenGenerator {

  static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

  private static final String ALGORITHM = "AWS4-HMAC-SHA256";
  private static final String RDS_SIGNING_NAME = "rds-db";
  private static final String TERMINATOR = "aws4_request";
  private static final String SIGNED_HEADERS = "host";
  private static final String EMPTY_PAYLOAD_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String UNRESERVED =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
  private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private final Supplier<AwsCredentials> credentials;
  private final Clock clock;

  @Inject
  RdsIamAuthenticationTokenGenerator(EcsTaskCredentialsProvider credentialsProvider) {
    this(credentialsProvider::resolveCredentials, Clock.systemUTC());
  }

  RdsIamAuthenticationTokenGenerator(Supplier<AwsCredentials> credentials) {
    this(credentials, Clock.systemUTC());
  }

  RdsIamAuthenticationTokenGenerator(Supplier<AwsCredentials> credentials, Clock clock) {
    this.credentials = credentials;
    this.clock = clock;
  }

  String generate(String host, int port, String username, String region) {
    String signingHost = validHost(host).toLowerCase(Locale.ROOT);
    require(port >= 1 && port <= 65_535, "port must be between 1 and 65535");
    requireNonBlank(username, "username");
    require(
        username.codePoints().noneMatch(Character::isISOControl),
        "username must not contain control characters");
    requireNonBlank(region, "region");
    require(validRegion(region), "region is invalid");

    AwsCredentials identity =
        requireNonNull(requireNonNull(credentials, "credentials provider").get(), "credentials");
    String accessKeyId = requireNonBlank(identity.accessKeyId(), "AWS access key ID");
    String secretAccessKey = requireNonBlank(identity.secretAccessKey(), "AWS secret access key");
    String sessionToken = identity.sessionToken();
    require(sessionToken == null || !sessionToken.isBlank(), "AWS session token must not be blank");

    Instant now = requireNonNull(clock, "clock").instant();
    String date = DATE.format(now);
    String timestamp = TIMESTAMP.format(now);
    String credentialScope = date + "/" + region + "/" + RDS_SIGNING_NAME + "/" + TERMINATOR;

    List<QueryParameter> parameters = new ArrayList<>();
    parameters.add(new QueryParameter("Action", "connect"));
    parameters.add(new QueryParameter("DBUser", username));
    parameters.add(new QueryParameter("X-Amz-Algorithm", ALGORITHM));
    parameters.add(new QueryParameter("X-Amz-Credential", accessKeyId + "/" + credentialScope));
    parameters.add(new QueryParameter("X-Amz-Date", timestamp));
    parameters.add(new QueryParameter("X-Amz-Expires", Long.toString(TOKEN_LIFETIME.toSeconds())));
    if (sessionToken != null) {
      parameters.add(new QueryParameter("X-Amz-Security-Token", sessionToken));
    }
    parameters.add(new QueryParameter("X-Amz-SignedHeaders", SIGNED_HEADERS));
    String canonicalQuery = canonicalQuery(parameters);
    String hostAndPort = signingHost + ":" + port;
    String canonicalRequest =
        "GET\n"
            + "/\n"
            + canonicalQuery
            + "\n"
            + "host:"
            + hostAndPort
            + "\n\n"
            + SIGNED_HEADERS
            + "\n"
            + EMPTY_PAYLOAD_SHA256;
    String stringToSign =
        ALGORITHM + "\n" + timestamp + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);
    byte[] signingKey = signingKey(secretAccessKey, date, region);
    String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));

    return hostAndPort + "/?" + canonicalQuery + "&X-Amz-Signature=" + signature;
  }

  private static String validHost(String host) {
    requireNonBlank(host, "host");
    require(validDnsName(host), "host is invalid");
    return host;
  }

  private static boolean validDnsName(String host) {
    if (host.length() > 253) {
      return false;
    }
    int labelLength = 0;
    boolean labelStartsWithLetterOrDigit = false;
    boolean previousWasLetterOrDigit = false;
    for (int index = 0; index < host.length(); index++) {
      char character = host.charAt(index);
      if (character == '.') {
        if (!labelStartsWithLetterOrDigit || !previousWasLetterOrDigit || labelLength > 63) {
          return false;
        }
        labelLength = 0;
        labelStartsWithLetterOrDigit = false;
        previousWasLetterOrDigit = false;
      } else {
        boolean letterOrDigit = isAsciiLetterOrDigit(character);
        if (!letterOrDigit && character != '-') {
          return false;
        }
        labelLength++;
        if (labelLength == 1) {
          labelStartsWithLetterOrDigit = letterOrDigit;
        }
        previousWasLetterOrDigit = letterOrDigit;
      }
    }
    return labelStartsWithLetterOrDigit && previousWasLetterOrDigit && labelLength <= 63;
  }

  private static boolean validRegion(String region) {
    if (region.length() > 64
        || !isLowercaseAsciiLetterOrDigit(region.charAt(0))
        || !isLowercaseAsciiLetterOrDigit(region.charAt(region.length() - 1))) {
      return false;
    }
    for (int index = 1; index < region.length() - 1; index++) {
      char character = region.charAt(index);
      if (!isLowercaseAsciiLetterOrDigit(character) && character != '-') {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetterOrDigit(char character) {
    return (character >= 'A' && character <= 'Z') || isLowercaseAsciiLetterOrDigit(character);
  }

  private static boolean isLowercaseAsciiLetterOrDigit(char character) {
    return (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9');
  }

  private static String canonicalQuery(List<QueryParameter> parameters) {
    return parameters.stream()
        .map(
            parameter ->
                new QueryParameter(
                    percentEncode(parameter.key()), percentEncode(parameter.value())))
        .sorted(Comparator.comparing(QueryParameter::key).thenComparing(QueryParameter::value))
        .map(parameter -> parameter.key() + "=" + parameter.value())
        .reduce((left, right) -> left + "&" + right)
        .orElse("");
  }

  private static String percentEncode(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length);
    for (byte rawByte : bytes) {
      int unsignedByte = rawByte & 0xff;
      char character = (char) unsignedByte;
      if (unsignedByte < 128 && UNRESERVED.indexOf(character) >= 0) {
        encoded.append(character);
      } else {
        encoded
            .append('%')
            .append(UPPER_HEX[unsignedByte >>> 4])
            .append(UPPER_HEX[unsignedByte & 0x0f]);
      }
    }
    return encoded.toString();
  }

  private static byte[] signingKey(String secretAccessKey, String date, String region) {
    byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
    byte[] regionKey = hmac(dateKey, region);
    byte[] serviceKey = hmac(regionKey, RDS_SIGNING_NAME);
    return hmac(serviceKey, TERMINATOR);
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(value)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(utf8(value));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    require(value != null && !value.isBlank(), name + " must not be blank");
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private record QueryParameter(String key, String value) {}
}
