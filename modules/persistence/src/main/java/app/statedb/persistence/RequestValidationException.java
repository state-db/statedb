package app.statedb.persistence;

/**
 * Shared exception for request-body / input validation failures across the API. Maps to HTTP 400
 * {@code {code, message}} via a single mapper, so endpoints throw this with a specific message
 * rather than each declaring its own validation exception + mapper pair.
 */
public class RequestValidationException extends RuntimeException {

  public RequestValidationException(String message) {
    super(message);
  }

  public RequestValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
