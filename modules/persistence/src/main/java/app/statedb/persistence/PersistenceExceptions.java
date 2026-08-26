package app.statedb.persistence;

import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;

public final class PersistenceExceptions {

  public static boolean isUniqueViolation(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getSQLException() != null
            && "23505".equals(violation.getSQLException().getSQLState());
      }
    }
    return false;
  }

  /** Returns the database constraint name when {@code throwable} wraps a unique violation. */
  public static Optional<String> uniqueConstraintName(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && violation.getSQLException() != null
          && "23505".equals(violation.getSQLException().getSQLState())) {
        return Optional.ofNullable(violation.getConstraintName());
      }
    }
    return Optional.empty();
  }

  private PersistenceExceptions() {}
}
