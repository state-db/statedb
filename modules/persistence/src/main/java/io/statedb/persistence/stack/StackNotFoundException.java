package io.statedb.persistence.stack;

/**
 * Thrown when a {@code (org, project, stack)} path triple does not resolve. Lives in persistence so
 * both HTTP-facing modules can throw the same type; each module supplies its own JAX-RS {@code
 * ExceptionMapper} so wire formats can diverge.
 */
public class StackNotFoundException extends RuntimeException {

  private final String qualifiedName;

  public StackNotFoundException(String org, String project, String stack) {
    super("stack not found");
    this.qualifiedName = org + "/" + project + "/" + stack;
  }

  public String qualifiedName() {
    return qualifiedName;
  }
}
