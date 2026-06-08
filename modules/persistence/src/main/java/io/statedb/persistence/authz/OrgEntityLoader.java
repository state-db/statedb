package io.statedb.persistence.authz;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.bootstrap.BootstrapProvisioner;
import io.statedb.persistence.identity.Organization;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class OrgEntityLoader implements EntityLoader<Organization> {

  @Override
  public Optional<LoadedResource> load(InvocationContext ctx, AuthenticatedPrincipal principal) {
    String orgName = pathParam(ctx, "org");
    Organization org = Organization.find("name", orgName).firstResult();
    if (org == null) {
      return Optional.empty();
    }
    if (!org.id.equals(principal.orgId()) && !isAdmin(principal)) {
      return Optional.empty();
    }

    EntityUID uid = CedarEntities.org(org.name);
    Entity entity =
        new Entity(
            uid,
            Map.of(
                "name",
                new PrimString(org.name),
                "viewer_role",
                CedarEntities.orgRole(org.name, "viewer"),
                "member_role",
                CedarEntities.orgRole(org.name, "member"),
                "owner_role",
                CedarEntities.orgRole(org.name, "owner")),
            Set.of());
    return Optional.of(new LoadedResource(new CedarResource(uid, Set.of(entity))));
  }

  @Override
  public RuntimeException notFound() {
    return new WebApplicationException(Response.Status.FORBIDDEN);
  }

  private static String pathParam(InvocationContext ctx, String name) {
    Annotation[][] annotations = ctx.getMethod().getParameterAnnotations();
    Object[] values = ctx.getParameters();
    for (int i = 0; i < annotations.length; i++) {
      for (Annotation annotation : annotations[i]) {
        if (annotation instanceof PathParam pathParam && name.equals(pathParam.value())) {
          return (String) values[i];
        }
      }
    }
    throw new IllegalStateException("missing @PathParam(\"" + name + "\")");
  }

  private static boolean isAdmin(AuthenticatedPrincipal principal) {
    return BootstrapProvisioner.BOOTSTRAP_ISS.equals(principal.iss())
        && BootstrapProvisioner.BOOTSTRAP_SUB.equals(principal.sub());
  }
}
