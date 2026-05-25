package io.statedb.persistence.update;

/** Thrown for a {@code POST /complete} body with a missing or non-terminal {@code status}. */
public class CompleteValidationException extends RuntimeException {

  public CompleteValidationException(String message) {
    super(message);
  }
}
