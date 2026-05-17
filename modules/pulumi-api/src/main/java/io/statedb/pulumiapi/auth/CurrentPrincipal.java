package io.statedb.pulumiapi.auth;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class CurrentPrincipal {

  private AuthenticatedPrincipal principal;

  // Package-private: only TokenAuthFilter writes.
  void set(AuthenticatedPrincipal principal) {
    this.principal = principal;
  }

  // null on routes the filter did not gate.
  public AuthenticatedPrincipal get() {
    return principal;
  }
}
