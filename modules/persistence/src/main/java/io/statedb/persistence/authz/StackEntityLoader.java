package io.statedb.persistence.authz;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.identity.AdminAllowList;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.stack.StackNotFoundException;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.stack.StackRefResolver;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.PathParam;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RequestScoped
public class StackEntityLoader implements EntityLoader<StackRef> {

  @Inject StackRefResolver resolver;

  @Inject PrincipalRoleResolver roleResolver;

  @Inject AdminAllowList adminAllowList;

  private String org;
  private String project;
  private String stack;

  @Override
  public Optional<LoadedResource> load(InvocationContext ctx, AuthenticatedPrincipal principal) {
    org = pathParam(ctx, "org");
    project = pathParam(ctx, "project");
    stack = pathParam(ctx, "stack");
    Optional<StackRef> ref = resolver.resolve(org, project, stack);
    if (ref.isEmpty()) {
      return Optional.empty();
    }
    StackRef r = ref.get();
    Optional<OrgMember.Role> role = roleResolver.resolve(principal, r.orgId());
    // Hard org boundary: the principal must have a role in the resource's org (or be admin).
    // A non-member never even sees the resource — the denial collapses to 404, not 403.
    if (role.isEmpty() && !adminAllowList.isAdmin(principal.iss(), principal.sub())) {
      return Optional.empty();
    }
    return Optional.of(new LoadedResource(stackResource(r), role.orElse(null)));
  }

  @Override
  public RuntimeException notFound() {
    return new StackNotFoundException(org, project, stack);
  }

  public static CedarResource stackResource(StackRef ref) {
    EntityUID orgUid = CedarEntities.org(ref.orgName());
    EntityUID projectUid = CedarEntities.project(ref.orgName(), ref.projectName());
    EntityUID stackUid = CedarEntities.stack(ref.orgName(), ref.projectName(), ref.stackName());

    // Each resource carries its resource name as an `id` attribute so policies can pattern-match it
    // IAM-style (e.g. a Deny on `resource.id like "statedb:stack:*/prod"`). The Cedar entity id and
    // the attribute are the same resource name.
    Entity org = new Entity(orgUid, idAttr(ResourceName.org(ref.orgName())), Set.of());
    Entity project =
        new Entity(
            projectUid,
            idAttr(ResourceName.project(ref.orgName(), ref.projectName())),
            Set.of(orgUid));
    Entity stack =
        new Entity(
            stackUid,
            idAttr(ResourceName.stack(ref.orgName(), ref.projectName(), ref.stackName())),
            Set.of(projectUid));

    Set<Entity> entities = new HashSet<>();
    entities.add(org);
    entities.add(project);
    entities.add(stack);
    return new CedarResource(stackUid, entities);
  }

  private static Map<String, com.cedarpolicy.value.Value> idAttr(String resourceName) {
    return Map.of("id", new PrimString(resourceName));
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
}
