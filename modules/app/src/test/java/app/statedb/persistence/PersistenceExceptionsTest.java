package app.statedb.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class PersistenceExceptionsTest {

  @Test
  void extractsConstraintNameFromWrappedUniqueViolation() {
    SQLException sql = new SQLException("duplicate", "23505");
    ConstraintViolationException violation =
        new ConstraintViolationException(
            "insert failed", sql, "insert into oidc_providers ...", "oidc_providers_name_key");

    RuntimeException wrapped = new RuntimeException(violation);

    assertTrue(PersistenceExceptions.isUniqueViolation(wrapped));
    assertEquals(
        "oidc_providers_name_key",
        PersistenceExceptions.uniqueConstraintName(wrapped).orElseThrow());
  }
}
