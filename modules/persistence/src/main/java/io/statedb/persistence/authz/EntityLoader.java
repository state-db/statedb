package io.statedb.persistence.authz;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import jakarta.interceptor.InvocationContext;
import java.util.Optional;

public interface EntityLoader<T> {

  Optional<LoadedResource> load(InvocationContext ctx, AuthenticatedPrincipal principal);

  RuntimeException notFound();
}
