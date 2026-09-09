package app.statedb.consoleapi.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.jboss.logging.Logger;

/**
 * Verifies a break-glass password against either hash format state-db accepts.
 *
 * <p>Two formats exist because two very different things produce the hash:
 *
 * <ul>
 *   <li><b>bcrypt Modular Crypt Format</b> ({@code $2a$}/{@code $2b$}/{@code $2y$}) — a human chose
 *       the password and hashed it themselves, typically {@code htpasswd -bnBC 12}. This is the
 *       compose and self-hosted path, where there is no secret store to generate from.
 *   <li><b>{@code $pbkdf2-sha256$<iterations>$<base64 salt>$<base64 derived key>}</b> — an
 *       installer generated a high-entropy password and derived the verifier without ever handing
 *       the plaintext to the application. The AWS CloudFormation stack does this in its bootstrap
 *       Lambda ({@code deploy/aws/cloudformation.yaml}), writing the password to Secrets Manager
 *       for the operator and only this hash to the parameter the task reads.
 * </ul>
 *
 * <p>Iteration count is read from the encoded hash rather than pinned here, so raising it in the
 * installer does not require an application release to stay able to verify existing stacks.
 *
 * <p>Every failure — unknown prefix, malformed encoding, wrong password — returns {@code false}
 * rather than throwing. The caller turns any negative into the same opaque 401, and distinguishing
 * "malformed hash" from "wrong password" in a response would leak how the stack was provisioned.
 */
public final class PasswordHashes {

  private static final Logger LOG = Logger.getLogger(PasswordHashes.class);

  private static final String PBKDF2_PREFIX = "$pbkdf2-sha256$";

  /** Matches {@code hashlib.pbkdf2_hmac("sha256", ...)} in the installer's Lambda. */
  private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

  /** Guards against a malformed hash requesting an unbounded amount of work. */
  private static final int MAX_ITERATIONS = 10_000_000;

  /**
   * Whether {@code password} verifies against {@code hash}. Cost is deliberate — bcrypt and PBKDF2
   * are both slow by construction — so call this off the event loop.
   */
  public static boolean matches(String password, String hash) {
    if (hash == null || hash.isBlank()) {
      return false;
    }
    try {
      return hash.startsWith(PBKDF2_PREFIX)
          ? pbkdf2Matches(password, hash)
          : BcryptUtil.matches(password, hash);
    } catch (RuntimeException e) {
      // BcryptUtil throws rather than returning false for anything it cannot parse. A hash this
      // process cannot evaluate has to fail the login, not the request: the installer's
      // placeholder sits in the parameter until the credential is generated, and every attempt
      // made in that window must still get the ordinary 401.
      LOG.warn("break-glass password hash could not be evaluated; login cannot succeed", e);
      return false;
    }
  }

  private static boolean pbkdf2Matches(String password, String hash) {
    // "$pbkdf2-sha256$<iterations>$<salt>$<key>" splits to a leading empty field plus four.
    String[] fields = hash.split("\\$");
    if (fields.length != 5) {
      LOG.warn("break-glass PBKDF2 hash is malformed; login cannot succeed");
      return false;
    }
    try {
      int iterations = Integer.parseInt(fields[2]);
      if (iterations < 1 || iterations > MAX_ITERATIONS) {
        LOG.warnf("break-glass PBKDF2 hash requests %d iterations; refusing", iterations);
        return false;
      }
      byte[] salt = Base64.getDecoder().decode(fields[3]);
      byte[] expected = Base64.getDecoder().decode(fields[4]);
      PBEKeySpec spec =
          new PBEKeySpec(password.toCharArray(), salt, iterations, expected.length * 8);
      try {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        return MessageDigest.isEqual(expected, factory.generateSecret(spec).getEncoded());
      } finally {
        spec.clearPassword();
      }
    } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
      LOG.warn("break-glass PBKDF2 hash could not be evaluated; login cannot succeed", e);
      return false;
    }
  }

  private PasswordHashes() {}
}
