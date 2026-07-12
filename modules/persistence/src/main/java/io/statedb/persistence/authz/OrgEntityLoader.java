package io.statedb.persistence.authz;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.identity.AdminAllowList;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class OrgEntityLoader implements EntityLoader<Organization> {

  @Inject PrincipalRoleResolver roleResolver;

  @Inject AdminAllowList adminAllowList;

  @Override
  public Optional<LoadedResource> load(InvocationContext ctx, AuthenticatedPrincipal principal) {
    String orgName = EntityLoader.pathParam(ctx, "org");
    Organization org = Organization.find("name", orgName).firstResult();
    if (org == null) {
      return Optional.empty();
    }
    Optional<OrgMember.Role> role = roleResolver.resolve(principal, org.id);
    if (role.isEmpty() && !adminAllowList.isAdmin(principal.iss(), principal.sub())) {
      return Optional.empty();
    }

    EntityUID uid = CedarEntities.org(org.name);
    Entity entity =
        new Entity(uid, Map.of("id", new PrimString(ResourceName.org(org.name))), Set.of());
    return Optional.of(
        new LoadedResource(new CedarResource(uid, Set.of(entity)), role.orElse(null), org));
  }

  @Override
  public RuntimeException notFound() {
    return new WebApplicationException(Response.Status.FORBIDDEN);
  }
}
