package app.statedb.consoleapi.auth;

import app.statedb.persistence.identity.UserUpserter;
import io.quarkus.arc.Unremovable;
import io.quarkus.oidc.AuthenticationCompletionAction;
import io.quarkus.security.AuthenticationCompletionException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Persists the console user once, inside Quarkus's successful OIDC code-flow completion. */
@ApplicationScoped
@Unremovable
public class OidcUserProvisioner implements AuthenticationCompletionAction {

  @Inject UserUpserter userUpserter;

  @Override
  public Uni<Void> action(AuthenticationCompletionContext context) {
    if (!(context.identity().getPrincipal() instanceof JsonWebToken jwt)
        || jwt.getIssuer() == null
        || jwt.getSubject() == null) {
      return Uni.createFrom()
          .failure(new AuthenticationCompletionException("OIDC identity is missing iss or sub"));
    }

    return context
        .requestContext()
        .runBlocking(
            () -> {
              userUpserter.upsertOnLogin(
                  jwt.getIssuer(), jwt.getSubject(), jwt.getClaim("email"), jwt.getClaim("name"));
              return context.identity();
            })
        .replaceWithVoid();
  }
}
