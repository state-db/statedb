package app.statedb.app;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;

/**
 * Base class for {@code @QuarkusTest} suites that touch PostgreSQL. Wipes test-owned tables and
 * resets test-mutable caches after each test while preserving startup-seeded rows such as {@code
 * service_secrets_keyset}. Concrete subclasses declare {@code @QuarkusTest} directly so Quarkus ArC
 * registers them as test beans.
 */
public abstract class DatabaseTest {

  @Inject protected TestDb db;

  @Inject protected EntityManager em;

  @AfterEach
  void resetDatabase() {
    db.reset();
  }
}
