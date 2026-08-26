package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class NoopEntityLoader implements EntityLoader<Object> {

  @Override
  public Optional<LoadedResource> load(AuthorizationPath path, AuthenticatedPrincipal principal) {
    return Optional.empty();
  }

  @Override
  public RuntimeException notFound() {
    return new IllegalStateException("NoopEntityLoader cannot load resources");
  }
}
