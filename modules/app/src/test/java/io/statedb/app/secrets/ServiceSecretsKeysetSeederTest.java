package io.statedb.app.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.secrets.ServiceSecretsKeysetSeeder;
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
    QuarkusTransaction.requiringNew().run(seeder::seed);
    QuarkusTransaction.requiringNew().run(seeder::seed);
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
