package io.statedb.consoleapi.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.authz.CurrentPrincipal;
import io.statedb.persistence.identity.User;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Populates {@code CurrentPrincipal} from the authenticated console session on {@link
 * SessionPrincipal}-annotated routes, giving them the same Cedar authorization path as the Pulumi
 * surface: a {@code console_session} credential with all-current-memberships scope, so {@code
 * PrincipalRoleResolver} looks the role up live per resource org.
 *
 * <p>The quarkus HTTP permission on {@code /api/*} has already authenticated the session; this
 * filter only bridges it. A session whose user row is missing (never completed provisioning) is
 * rejected rather than treated as anonymous.
 */
@Provider
@SessionPrincipal
@Priority(Priorities.AUTHENTICATION)
@Blocking
public class SessionPrincipalFilter implements ContainerRequestFilter {

  @Inject SecurityIdentity identity;

  @Inject CurrentPrincipal currentPrincipal;

  @Override
  public void filter(ContainerRequestContext ctx) {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
      return;
    }
    User user =
        User.<User>find("iss = ?1 and sub = ?2", jwt.getIssuer(), jwt.getSubject()).firstResult();
    if (user == null) {
      ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
      return;
    }
    currentPrincipal.set(
        AuthenticatedPrincipal.consoleSession(user.id, jwt.getIssuer(), jwt.getSubject()));
  }
}
