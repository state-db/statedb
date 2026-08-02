package app.statedb.persistence.identity;

public class LastOwnerException extends RuntimeException {
  public LastOwnerException() {
    super("an organization must have at least one owner");
  }
}
