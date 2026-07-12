package io.statedb.persistence.auth;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
      return HexFormat.of().formatHex(md.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
