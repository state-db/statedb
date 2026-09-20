package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.stack.StackNotFoundException;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRefResolver;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RequestScoped
public class StackEntityLoader implements EntityLoader<StackRef> {

  @Inject StackRefResolver resolver;

  @Inject StackReadAuthorizer readAuthorizer;

  private String org;
  private String project;
  private String stack;

  @Override
  public Optional<LoadedResource> load(AuthorizationPath path, AuthenticatedPrincipal principal) {
    org = path.org();
    project = path.project();
    stack = path.stack();
    Optional<StackRef> ref = resolver.resolve(org, project, stack);
    if (ref.isEmpty()) {
      return Optional.empty();
    }
    // Read-visibility boundary: a stack the principal cannot READ does not resolve — the denial
    // collapses to 404, not 403 (a 403 would confirm the hidden stack exists). This applies the
    // same predicate as the list finders (design/stack-sharing.md §3/§9): non-members see nothing;
    // guests see only granted stacks; members/viewers additionally lose private stacks unless
    // granted. 403 stays reserved for within-visible-scope denials on non-read actions.
    return readAuthorizer.visibleResource(ref.get(), principal);
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
    // the attribute are the same resource name. Stacks additionally carry `visibility`, which the
    // viewer policy's `when` gates org-wide read on.
    Entity org = new Entity(orgUid, idAttr(ResourceName.org(ref.orgName())), Set.of());
    Entity project =
        new Entity(
            projectUid,
            idAttr(ResourceName.project(ref.orgName(), ref.projectName())),
            Set.of(orgUid));
    Entity stack =
        new Entity(
            stackUid,
            Map.of(
                "id",
                new PrimString(
                    ResourceName.stack(ref.orgName(), ref.projectName(), ref.stackName())),
                "visibility",
                new PrimString(ref.visibility())),
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
}
