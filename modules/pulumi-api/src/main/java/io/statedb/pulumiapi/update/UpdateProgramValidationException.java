package io.statedb.pulumiapi.update;

/** Thrown when a Pulumi update request body contradicts the stack path being updated. */
public class UpdateProgramValidationException extends RuntimeException {

  public UpdateProgramValidationException(String message) {
    super(message);
  }
}
