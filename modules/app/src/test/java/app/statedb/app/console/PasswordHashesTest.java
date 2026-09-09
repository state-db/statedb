package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.consoleapi.auth.PasswordHashes;
import io.quarkus.elytron.security.common.BcryptUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract for the two break-glass hash formats.
 *
 * <p>The PBKDF2 vectors are not generated here — they are literal outputs of {@code
 * hashlib.pbkdf2_hmac} as the installer's bootstrap Lambda calls it in {@code
 * deploy/aws/cloudformation.yaml}. Deriving them with the same Java code under test would only
 * prove self-consistency; pinning the Python output is what catches a divergence between the two
 * implementations, which would lock every AWS operator out of a fresh stack.
 */
class PasswordHashesTest {

  /** {@code derive("glass-recovery-pw")} with the salt fixed to {@code statedb-fixture0}. */
  private static final String PBKDF2_HASH =
      "$pbkdf2-sha256$600000$c3RhdGVkYi1maXh0dXJlMA==$p/OYbkEBUe4q5qlL9mh8CCCbJJ95ZRGFIiAJlE7qQFo=";

  /** The same password and salt at 1000 iterations, proving the count is read from the hash. */
  private static final String LOW_ITERATION_HASH =
      "$pbkdf2-sha256$1000$c3RhdGVkYi1maXh0dXJlMA==$YLKd3L8MROqqeVtcXOD9wMs9/WL5WuJ6ji2jjAlmtak=";

  private static final String PASSWORD = "glass-recovery-pw";

  @Test
  void acceptsInstallerGeneratedPbkdf2Hash() {
    assertTrue(PasswordHashes.matches(PASSWORD, PBKDF2_HASH));
  }

  @Test
  void readsIterationCountFromTheHashRatherThanAssumingIt() {
    assertTrue(PasswordHashes.matches(PASSWORD, LOW_ITERATION_HASH));
    // Same password and salt at a different cost must not cross-verify.
    assertFalse(PasswordHashes.matches(PASSWORD, swapIterations(PBKDF2_HASH, "1000")));
  }

  @Test
  void rejectsWrongPasswordAgainstPbkdf2() {
    assertFalse(PasswordHashes.matches("glass-recovery-pX", PBKDF2_HASH));
    assertFalse(PasswordHashes.matches("", PBKDF2_HASH));
  }

  @Test
  void stillAcceptsBcryptForSelfHostedInstalls() {
    String bcrypt = BcryptUtil.bcryptHash(PASSWORD);
    assertTrue(PasswordHashes.matches(PASSWORD, bcrypt));
    assertFalse(PasswordHashes.matches("wrong", bcrypt));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "$pbkdf2-sha256$600000$c3RhdGVkYi1maXh0dXJlMA==", // truncated
        "$pbkdf2-sha256$600000$$", // empty salt and key
        "$pbkdf2-sha256$notanumber$c3RhdGVkYi1maXh0dXJlMA==$YLKd3L8=", // unparsable cost
        "$pbkdf2-sha256$0$c3RhdGVkYi1maXh0dXJlMA==$YLKd3L8=", // zero cost
        "$pbkdf2-sha256$99999999$c3RhdGVkYi1maXh0dXJlMA==$YLKd3L8=", // cost beyond the cap
        "$pbkdf2-sha256$600000$not base64!$YLKd3L8=", // undecodable salt
        "pending-bootstrap", // the CloudFormation placeholder, before the Lambda overwrites it
        "",
      })
  void rejectsMalformedHashesWithoutThrowing(String hash) {
    assertFalse(PasswordHashes.matches(PASSWORD, hash));
  }

  @Test
  void rejectsNullHash() {
    assertFalse(PasswordHashes.matches(PASSWORD, null));
  }

  private static String swapIterations(String hash, String iterations) {
    String[] fields = hash.split("\\$");
    return "$" + fields[1] + "$" + iterations + "$" + fields[3] + "$" + fields[4];
  }
}
