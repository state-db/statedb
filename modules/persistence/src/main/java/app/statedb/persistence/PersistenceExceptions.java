package app.statedb.persistence;

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

  private PersistenceExceptions() {}
}
