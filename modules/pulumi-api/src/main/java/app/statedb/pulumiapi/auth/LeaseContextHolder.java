package app.statedb.pulumiapi.auth;

import app.statedb.persistence.lock.LeaseContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.WebApplicationException;

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

  /**
   * Returns the lease identity established by {@link LeaseTokenFilter}.
   *
   * <p>Lease-scoped resource methods are reachable only after the filter has validated the token,
   * stack path, and update id. The missing-context branch is therefore defensive and preserves the
   * same Pulumi lease-conflict response used by filter rejections.
   */
  public LeaseContext require() {
    if (context == null) {
      throw new WebApplicationException(LeaseConflictResponse.response());
    }
    return context;
  }
}
