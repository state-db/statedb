package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads the singleton site resource for global console administration decisions. */
@ApplicationScoped
public class SiteEntityLoader implements EntityLoader<SiteRef> {

  @Override
  public Optional<LoadedResource> load(
      InvocationContext context, AuthenticatedPrincipal principal) {
    EntityUID uid = CedarEntities.site();
    Entity entity = new Entity(uid, Map.of("id", new PrimString(ResourceName.site())), Set.of());
    return Optional.of(
        new LoadedResource(new CedarResource(uid, Set.of(entity)), null, SiteRef.INSTANCE));
  }

  @Override
  public RuntimeException notFound() {
    return new IllegalStateException("the singleton Site resource is always present");
  }
}
