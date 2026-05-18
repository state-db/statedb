package io.statedb.persistence.stack;

/**
 * Thrown when a {@code (org, project, stack)} triple already exists. Surfaced from {@link
 * StackCreator} when the {@code stacks(project_id, name)} UNIQUE constraint trips at flush time —
 * i.e. when a concurrent caller won the race between the pre-insert existence check and the insert.
 */
public class StackAlreadyExistsException extends RuntimeException {

  private final String qualifiedName;

  public StackAlreadyExistsException(String org, String project, String stack) {
    super("stack already exists");
    this.qualifiedName = org + "/" + project + "/" + stack;
  }

  public String qualifiedName() {
    return qualifiedName;
  }
}
