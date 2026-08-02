package app.statedb.pulumiapi.auth;

import app.statedb.persistence.lock.LeaseContext;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class LeaseContextHolder {

  private LeaseContext context;

  // Package-private: only LeaseTokenFilter writes.
  void set(LeaseContext context) {
    this.context = context;
  }

  // null on routes the filter did not gate.
  public LeaseContext get() {
    return context;
  }
}
