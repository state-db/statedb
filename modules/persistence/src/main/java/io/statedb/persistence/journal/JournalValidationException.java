package io.statedb.persistence.journal;

/**
 * Thrown for malformed journal entries — unknown {@code kind}, missing required fields,
 * non-positive {@code sequenceID}. Maps to HTTP 400. Validation runs before any DB writes so a
 * rejection writes zero rows.
 */
public class JournalValidationException extends RuntimeException {

  public JournalValidationException(String message) {
    super(message);
  }

  public JournalValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
