package io.statedb.persistence.auth;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The single definition of how a raw API token maps to its stored {@code api_tokens.token_hash}.
 *
 * <p>Both the mint path ({@link CliTokenMinter}) and the verify path ({@code TokenAuthFilter}) must
 * agree byte-for-byte, so the algorithm lives in one place. Raw tokens are high-entropy random
 * strings, so a fast unsalted SHA-256 is sufficient — there is nothing to brute-force and no need
 * for a slow KDF (unlike a password).
 */
@ApplicationScoped
public class ApiTokenHasher {

  public String hash(String rawToken) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
