package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class CurrentPrincipal {

  private AuthenticatedPrincipal principal;

  public void set(AuthenticatedPrincipal principal) {
    this.principal = principal;
  }

  public AuthenticatedPrincipal get() {
    return principal;
  }
}
