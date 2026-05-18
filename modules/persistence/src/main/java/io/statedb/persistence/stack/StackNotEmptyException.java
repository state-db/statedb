package io.statedb.persistence.stack;

/** Thrown when a stack with journaled state changes is deleted without Pulumi's force flag. */
public class StackNotEmptyException extends RuntimeException {

  public StackNotEmptyException(String org, String project, String stack) {
    super("Stack is not empty: " + org + "/" + project + "/" + stack);
  }
}
