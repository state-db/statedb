package app.statedb.app.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.statedb.persistence.secrets.ServiceSecretsAead;
import com.google.crypto.tink.Aead;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;

/**
 * Unit-level checks of the {@link Aead} primitive resolved from the seeded singleton keyset. The
 * Quarkus startup observer in {@link app.statedb.persistence.secrets.ServiceSecretsKeysetSeeder}
 * has run by the time the test container is up, so the row is in place.
 */
@QuarkusTest
class ServiceSecretsAeadTest {

  @Inject ServiceSecretsAead holder;

  @Test
  void roundTripReturnsOriginalPlaintext() throws GeneralSecurityException {
    Aead aead = holder.aead();
    byte[] plaintext = "hello world".getBytes(StandardCharsets.UTF_8);
    byte[] aad = new byte[16];

    byte[] ciphertext = aead.encrypt(plaintext, aad);
    assertNotNull(ciphertext);
    assertArrayEquals(plaintext, aead.decrypt(ciphertext, aad));
  }

  @Test
  void twoEncryptsOfSamePlaintextProduceDistinctCiphertexts() throws GeneralSecurityException {
    Aead aead = holder.aead();
    byte[] plaintext = "same input".getBytes(StandardCharsets.UTF_8);
    byte[] aad = new byte[16];

    byte[] a = aead.encrypt(plaintext, aad);
    byte[] b = aead.encrypt(plaintext, aad);
    assertFalse(java.util.Arrays.equals(a, b), "AES-GCM nonces must make ciphertexts distinct");
  }

  @Test
  void aadMismatchFailsTagCheck() throws GeneralSecurityException {
    Aead aead = holder.aead();
    byte[] plaintext = "stack-bound".getBytes(StandardCharsets.UTF_8);
    byte[] aadA = new byte[16];
    byte[] aadB = new byte[16];
    aadB[0] = 1;

    byte[] ciphertext = aead.encrypt(plaintext, aadA);
    assertThrows(GeneralSecurityException.class, () -> aead.decrypt(ciphertext, aadB));
  }

  @Test
  void holderReturnsSameInstance() {
    assertEquals(holder.aead(), holder.aead());
  }
}
