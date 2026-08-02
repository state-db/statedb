package app.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.util.Optional;

/**
 * Opens a CDI request context for the blocking database lookup performed by Quarkus's dynamic OIDC
 * tenant resolver. Tenant resolution runs before JAX-RS and its {@code runBlocking} callback does
 * not otherwise have the Hibernate session that a normal REST request receives.
 */
@ApplicationScoped
public class EnabledOidcProviderLookup {

  @ActivateRequestContext
  public Optional<OidcProvider.ConnectionConfig> find(String name) {
    return OidcProvider.findEnabled(name);
  }
}
