package io.statedb.persistence.authz;

public class AuthorizationDeniedException extends RuntimeException {

  public AuthorizationDeniedException(String action) {
    super("authorization denied for " + action);
  }
}
