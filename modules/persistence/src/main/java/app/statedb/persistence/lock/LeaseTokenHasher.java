package app.statedb.persistence.lock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Single place that hashes lease tokens for storage and comparison. The wire shape is a UUID
 * (canonical lowercase, hyphenated); the stored shape is SHA-256 of that UUID's string
 * representation. Plaintext is never stored.
 *
 * <p>{@link #constantTimeEquals(byte[], byte[])} wraps {@link MessageDigest#isEqual} so callers
 * don't have to remember which equality function is timing-safe.
 */
public final class LeaseTokenHasher {

  private LeaseTokenHasher() {}

  public static byte[] hash(UUID token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(token.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }
}
