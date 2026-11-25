package app.statedb.app.secrets;

import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.persistence.secrets.ServiceSecretsKeysetSeeder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ServiceSecretsKeysetSeederTest {

  @Inject ServiceSecretsKeysetSeeder seeder;

  @Inject EntityManager em;

  @Test
  void exactlyOneRowAfterStartup() {
    Number count =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM service_secrets_keyset").getSingleResult();
    assertEquals(1L, count.longValue(), "bootstrap seeded exactly one keyset row");
  }

  @Test
  void rerunningSeedIsIdempotent() {
    byte[] before = currentMaterial();
    tx(seeder::seed);
    tx(seeder::seed);
    byte[] after = currentMaterial();

    assertArrayEquals(before, after, "key material must not change on re-seed");
    Number count =
        (Number)
            em.createNativeQuery("SELECT count(*) FROM service_secrets_keyset").getSingleResult();
    assertEquals(1L, count.longValue(), "singleton row count must stay at 1");
  }

  private byte[] currentMaterial() {
    return (byte[])
        em.createNativeQuery("SELECT key_material FROM service_secrets_keyset WHERE id = 1")
            .getSingleResult();
  }
}
