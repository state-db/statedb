package app.statedb.consoleapi.auth;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Login-time half of break-glass: verifies the form POST to {@code /api/auth/local} against {@link
 * LocalGlass} policy. Every failure mode — gate closed, non-loopback source, wrong password — is
 * the same {@link AuthenticationFailedException}, which the location-less form mechanism turns into
 * a bare 401. The submitted username is ignored; a successful login is always the bootstrap admin.
 */
@ApplicationScoped
public class LocalGlassIdentityProvider
    implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

  @Inject LocalGlass glass;

  @Override
  public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
    return UsernamePasswordAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      UsernamePasswordAuthenticationRequest request, AuthenticationRequestContext context) {
    RoutingContext routing = HttpSecurityUtils.getRoutingContextAttribute(request);
    SocketAddress remote = routing == null ? null : routing.request().remoteAddress();
    return context.runBlocking(
        () -> {
          if (!glass.originAllowed(remote) || !glass.active()) {
            throw new AuthenticationFailedException("break-glass login is not available");
          }
          if (!glass.verify(request.getPassword().getPassword())) {
            throw new AuthenticationFailedException("wrong break-glass password");
          }
          glass.ensureAdminUser();
          return QuarkusSecurityIdentity.builder()
              .setPrincipal(LocalGlassPrincipal.INSTANCE)
              .build();
        });
  }
}
