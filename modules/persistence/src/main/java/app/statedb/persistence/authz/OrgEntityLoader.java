package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class OrgEntityLoader implements EntityLoader<Organization> {

  @Inject PrincipalRoleResolver roleResolver;

  @Inject AdminAllowList adminAllowList;

  @Override
  public Optional<LoadedResource> load(AuthorizationPath path, AuthenticatedPrincipal principal) {
    String orgName = path.org();
    Organization org = findOrg(orgName);
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

  private static Organization findOrg(String ref) {
    try {
      Organization byId = Organization.findById(UUID.fromString(ref));
      if (byId != null) return byId;
    } catch (IllegalArgumentException ignored) {
      // Console URLs use names; membership administration uses stable UUIDs.
    }
    return Organization.find("name", ref).firstResult();
  }

  @Override
  public RuntimeException notFound() {
    return new WebApplicationException(Response.Status.FORBIDDEN);
  }
}
