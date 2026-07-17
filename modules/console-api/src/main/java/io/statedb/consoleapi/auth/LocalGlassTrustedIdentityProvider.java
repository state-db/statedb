package io.statedb.consoleapi.auth;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Cookie-restore half of break-glass: the form mechanism decrypts its session cookie and asks this
 * provider to rebuild the identity from the stored principal name. The {@link LocalGlass#active()}
 * re-check on every restore is the self-destruct — enabling an OIDC provider invalidates all
 * outstanding break-glass sessions immediately, not at cookie expiry.
 */
@ApplicationScoped
public class LocalGlassTrustedIdentityProvider
    implements IdentityProvider<TrustedAuthenticationRequest> {

  @Inject LocalGlass glass;

  @Override
  public Class<TrustedAuthenticationRequest> getRequestType() {
    return TrustedAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
    if (!LocalGlassPrincipal.COOKIE_PRINCIPAL.equals(request.getPrincipal())) {
      return Uni.createFrom()
          .failure(new AuthenticationFailedException("unknown break-glass principal"));
    }
    return context.runBlocking(
        () -> {
          if (!glass.active()) {
            throw new AuthenticationFailedException("break-glass sessions are disabled");
          }
          return QuarkusSecurityIdentity.builder()
              .setPrincipal(LocalGlassPrincipal.INSTANCE)
              .build();
        });
  }
}
