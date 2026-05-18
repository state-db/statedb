package io.statedb.persistence.stack;

/** Thrown when a stack with journaled state changes is deleted without Pulumi's force flag. */
public class StackNotEmptyException extends RuntimeException {

  private final String qualifiedName;

  public StackNotEmptyException(String org, String project, String stack) {
    super("stack is not empty");
    this.qualifiedName = org + "/" + project + "/" + stack;
  }

  public String qualifiedName() {
    return qualifiedName;
  }
}
