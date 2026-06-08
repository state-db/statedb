package io.statedb.persistence.authz;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import java.util.Optional;

@ApplicationScoped
public class NoopEntityLoader implements EntityLoader<Object> {

  @Override
  public Optional<LoadedResource> load(InvocationContext ctx, AuthenticatedPrincipal principal) {
    return Optional.empty();
  }

  @Override
  public RuntimeException notFound() {
    return new IllegalStateException("NoopEntityLoader cannot load resources");
  }
}
