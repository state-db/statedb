package app.statedb.consoleapi.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed double-submit CSRF token for the CLI-login approval form. The cookie carries the random
 * token; the hidden form field carries its HMAC. A forger can neither read the {@code HttpOnly}
 * cookie nor compute the signature without the derived key.
 *
 * <p>{@link #issue(String, boolean)} reuses the token already in the browser's cookie when there is
 * one, so a second approval page does not invalidate the form still open in an earlier tab. For the
 * same reason the cookie is never actively expired: it carries no authority on its own — an
 * approval also needs the single-use blob signed by {@link CliLoginApprovalSigner} — and clearing
 * it when one flow finishes would break every concurrent flow.
 */
@ApplicationScoped
public class CliLoginCsrf {

  static final String FIELD = "state-db-csrf";
  static final String COOKIE = "state-db-csrf";
  private static final String KEY_DERIVATION_LABEL = "state-db:cli-login-csrf";
  private static final String COOKIE_PATH = "/api/auth/cli-login";
  private static final int TOKEN_BYTES = 32;
  private static final int MAX_AGE_SECONDS = 2 * 60 * 60;

  private final SecureRandom secureRandom = new SecureRandom();

  @Inject SessionEncryptionSecret sessionSecret;

  private volatile SecretKeySpec derivedKey;

  /** {@code formValue} goes in the hidden field; {@code cookie} carries the token it signs. */
  public record Issued(String fieldName, String formValue, NewCookie cookie) {}

  Issued issue(String existingCookie, boolean secureCookie) {
    byte[] tokenBytes = decodeToken(existingCookie);
    if (tokenBytes == null) {
      tokenBytes = new byte[TOKEN_BYTES];
      secureRandom.nextBytes(tokenBytes);
    }
    return new Issued(
        FIELD,
        sign(tokenBytes),
        // Re-sent even when reused, to refresh the max-age while the flow is active.
        new NewCookie.Builder(COOKIE)
            .value(b64(tokenBytes))
            .path(COOKIE_PATH)
            .maxAge(MAX_AGE_SECONDS)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite(NewCookie.SameSite.LAX)
            .build());
  }

  boolean valid(String formValue, String cookieToken) {
    if (formValue == null || formValue.isBlank()) {
      return false;
    }
    byte[] tokenBytes = decodeToken(cookieToken);
    if (tokenBytes == null) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(tokenBytes).getBytes(StandardCharsets.UTF_8),
        formValue.getBytes(StandardCharsets.UTF_8));
  }

  /** The token bytes carried by a cookie value, or null if it is absent or malformed. */
  private static byte[] decodeToken(String cookieValue) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return null;
    }
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(cookieValue);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return bytes.length == TOKEN_BYTES ? bytes : null;
  }

  private String sign(byte[] tokenBytes) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(signingKey());
      return b64(mac.doFinal(tokenBytes));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  // Purpose-bound subkey; the raw session encryption secret is not reused directly for CSRF.
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

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
