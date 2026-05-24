package io.statedb.persistence.events;

/**
 * Thrown for malformed event batches — missing/negative {@code sequence}, missing or ambiguous kind
 * envelope. Maps to HTTP 400. Validation runs before any DB writes so a rejection writes zero rows.
 */
public class EventBatchValidationException extends RuntimeException {

  public EventBatchValidationException(String message) {
    super(message);
  }

  public EventBatchValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
