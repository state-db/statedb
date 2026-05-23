package io.statedb.pulumiapi.auth;

import io.statedb.persistence.lock.LeaseContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Emits one structured log line per lease-scoped request. The "who" lives on {@code
 * stack_updates.started_by_*} indexed by {@code update_id}; this log captures the per-action
 * timeline (lease, request, outcome) so an operator can join the two to answer "who did what when"
 * without scanning Pulumi CLI output.
 *
 * <p>No token material is logged — {@link LeaseContext} carries the surrogate lease id, not the
 * bearer.
 */
@Provider
public class LeaseAuditLogger implements ContainerResponseFilter {

  private static final Logger LOG = Logger.getLogger("lease-audit");

  @Inject LeaseContextHolder holder;

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    String path = request.getUriInfo().getPath();
    String normalized = path.startsWith("/") ? path : "/" + path;
    if (!LeaseRouteMatcher.isLeaseScoped(request.getMethod(), normalized)) {
      return;
    }

    LeaseContext ctx = holder.get();
    int status = response.getStatus();
    if (ctx == null) {
      LOG.infof(
          "lease_audit method=%s path=%s status=%d outcome=rejected",
          request.getMethod(), normalized, status);
    } else {
      LOG.infof(
          "lease_audit method=%s path=%s status=%d lease_id=%s stack_id=%s update_id=%s",
          request.getMethod(), normalized, status, ctx.leaseId(), ctx.stackId(), ctx.updateId());
    }
  }
}
