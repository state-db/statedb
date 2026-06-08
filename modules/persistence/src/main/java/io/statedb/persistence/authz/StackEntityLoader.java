package io.statedb.persistence.authz;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.bootstrap.BootstrapProvisioner;
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
    if (!r.orgId().equals(principal.orgId()) && !isAdmin(principal)) {
      return Optional.empty();
    }
    return Optional.of(new LoadedResource(stackResource(r)));
  }

  @Override
  public RuntimeException notFound() {
    return new StackNotFoundException(org, project, stack);
  }

  public static CedarResource stackResource(StackRef ref) {
    EntityUID orgUid = CedarEntities.org(ref.orgName());
    EntityUID projectUid = CedarEntities.project(ref.orgName(), ref.projectName());
    EntityUID stackUid = CedarEntities.stack(ref.orgName(), ref.projectName(), ref.stackName());

    Entity org =
        new Entity(
            orgUid,
            Map.of(
                "name",
                new PrimString(ref.orgName()),
                "viewer_role",
                CedarEntities.orgRole(ref.orgName(), "viewer"),
                "member_role",
                CedarEntities.orgRole(ref.orgName(), "member"),
                "owner_role",
                CedarEntities.orgRole(ref.orgName(), "owner")),
            Set.of());
    Entity project =
        new Entity(
            projectUid,
            Map.of(
                "org_name",
                new PrimString(ref.orgName()),
                "name",
                new PrimString(ref.projectName())),
            Set.of(orgUid));
    Entity stack =
        new Entity(
            stackUid,
            Map.of(
                "org_name",
                new PrimString(ref.orgName()),
                "project_name",
                new PrimString(ref.projectName()),
                "name",
                new PrimString(ref.stackName()),
                "viewer_role",
                CedarEntities.orgRole(ref.orgName(), "viewer"),
                "member_role",
                CedarEntities.orgRole(ref.orgName(), "member"),
                "owner_role",
                CedarEntities.orgRole(ref.orgName(), "owner")),
            Set.of(projectUid));

    Set<Entity> entities = new HashSet<>();
    entities.add(org);
    entities.add(project);
    entities.add(stack);
    return new CedarResource(stackUid, entities);
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
