package io.statedb.consoleapi.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies the CLI-login approval blob that rides the hidden form field between {@code
 * GET /api/auth/cli-login} (render the approval page) and {@code POST /api/auth/cli-login/approve}
 * (mint the token). The blob binds the loopback port, the CLI's nonce, and the approving user's
 * {@code iss}/{@code sub}, and expires after {@link #TTL}.
 *
 * <p>The HMAC key is derived from the session encryption secret with a fixed label, so the raw
 * secret is never used for two purposes (quarkus-oidc already uses it to encrypt the session
 * cookie).
 *
 * <p>{@link #consume(String)} is single-use: a signed request that already minted (or was
 * cancelled) is rejected on replay. The consumed set is in-memory, so behind a load balancer the
 * guarantee is per pod — a cross-pod replay is still bounded by the TTL and by requiring the
 * victim's own authenticated session, which is acceptable for this flow.
 */
@ApplicationScoped
public class CliLoginApprovalSigner {

  static final Duration TTL = Duration.ofMinutes(10);

  private static final String KEY_DERIVATION_LABEL = "state-db:cli-login-approval";

  @Inject SessionEncryptionSecret sessionSecret;

  private volatile SecretKeySpec derivedKey;

  // requestId -> approval expiry epoch second; pruned on every consume.
  private final ConcurrentHashMap<String, Long> consumed = new ConcurrentHashMap<>();

  /**
   * The approved CLI-login request, as carried in the signed form field. {@code requestId} is
   * random per rendered approval page — it is the single-use key, and it makes every signed blob
   * unique even for identical port/nonce/user issued in the same second.
   */
  public record CliLoginApproval(
      String requestId,
      int port,
      String nonce,
      String description,
      String iss,
      String sub,
      Instant expiresAt) {

    public static CliLoginApproval issue(
        int port, String nonce, String description, String iss, String sub) {
      return new CliLoginApproval(
          UUID.randomUUID().toString(),
          port,
          nonce,
          description,
          iss,
          sub,
          Instant.now().plus(TTL));
    }

    private String encode() {
      return String.join(
          "\n",
          requestId,
          Integer.toString(port),
          b64(nonce),
          b64(description == null ? "" : description),
          b64(iss),
          b64(sub),
          Long.toString(expiresAt.getEpochSecond()));
    }

    private static CliLoginApproval decode(String encoded) {
      String[] parts = encoded.split("\n", -1);
      if (parts.length != 7) {
        throw badRequest();
      }
      int port = CliLoginResource.parsePort(parts[1]);
      if (port == -1) {
        throw badRequest();
      }
      String nonce = new String(b64decode(parts[2]), StandardCharsets.UTF_8);
      if (CliLoginResource.isBlank(nonce)) {
        throw badRequest();
      }
      try {
        return new CliLoginApproval(
            parts[0],
            port,
            nonce,
            new String(b64decode(parts[3]), StandardCharsets.UTF_8),
            new String(b64decode(parts[4]), StandardCharsets.UTF_8),
            new String(b64decode(parts[5]), StandardCharsets.UTF_8),
            Instant.ofEpochSecond(Long.parseLong(parts[6])));
      } catch (NumberFormatException e) {
        throw badRequest();
      }
    }
  }

  public String sign(CliLoginApproval approval) {
    String payload = b64(approval.encode());
    return payload + "." + b64(hmac(payload));
  }

  /**
   * Verifies the signature and expiry, marks the request consumed, and returns the approval.
   * Rejects (400) anything malformed, forged, expired, or already consumed.
   */
  public CliLoginApproval consume(String signedRequest) {
    if (signedRequest == null || signedRequest.isBlank()) {
      throw badRequest();
    }
    int dot = signedRequest.indexOf('.');
    if (dot <= 0 || dot != signedRequest.lastIndexOf('.')) {
      throw badRequest();
    }
    String payload = signedRequest.substring(0, dot);
    String signature = signedRequest.substring(dot + 1);
    byte[] expected = hmac(payload);
    if (!MessageDigest.isEqual(b64decode(signature), expected)) {
      throw badRequest();
    }
    CliLoginApproval approval =
        CliLoginApproval.decode(new String(b64decode(payload), StandardCharsets.UTF_8));
    long nowEpochSecond = Instant.now().getEpochSecond();
    if (approval.expiresAt().getEpochSecond() < nowEpochSecond) {
      throw badRequest();
    }
    consumed.values().removeIf(expiry -> expiry < nowEpochSecond);
    if (consumed.putIfAbsent(approval.requestId(), approval.expiresAt().getEpochSecond()) != null) {
      throw badRequest();
    }
    return approval;
  }

  private byte[] hmac(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(signingKey());
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  // derivedKey = HMAC(sessionSecret, label): a purpose-bound subkey, so approval signatures can
  // never interact with the cookie encryption that uses the same operator-provided secret.
  // Benignly racy: concurrent first calls derive the same key.
  private SecretKeySpec signingKey() throws NoSuchAlgorithmException, InvalidKeyException {
    SecretKeySpec key = derivedKey;
    if (key == null) {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(sessionSecret.value().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      key =
          new SecretKeySpec(
              mac.doFinal(KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
      derivedKey = key;
    }
    return key;
  }

  private static String b64(String s) {
    return b64(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] b64decode(String s) {
    try {
      return Base64.getUrlDecoder().decode(s);
    } catch (IllegalArgumentException e) {
      throw badRequest();
    }
  }

  private static WebApplicationException badRequest() {
    return new WebApplicationException(Response.Status.BAD_REQUEST);
  }
}
