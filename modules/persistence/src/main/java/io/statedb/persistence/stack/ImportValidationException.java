package io.statedb.persistence.stack;

public class ImportValidationException extends RuntimeException {

  public ImportValidationException(String message) {
    super(message);
  }
}
