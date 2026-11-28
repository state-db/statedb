package app.statedb.app.secrets;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.secrets.ServiceSecretsKeysetSeeder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ServiceSecretsKeysetSeederTest extends DatabaseTest {

  @Inject ServiceSecretsKeysetSeeder seeder;

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
    requiringNew().run(seeder::seed);
    requiringNew().run(seeder::seed);
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
