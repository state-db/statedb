package io.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import java.util.Optional;

/**
 * Resolves a {@code (org, project, stack)} path triple to a {@link StackRef}. Lives here, not in
 * any HTTP-shaped module, because both {@code pulumi-api} and {@code console-api} need the same
 * tenant-isolated JOIN; duplicating it would be a security risk if the two copies drift.
 */
@ApplicationScoped
public class StackRefResolver {

  @Inject EntityManager em;

  @SuppressWarnings(
      "null") // JPA TypedQuery generics lack nullability annotations in static analysis
  public Optional<StackRef> resolve(String org, String project, String stack) {
    try {
      StackRef ref =
          em.createQuery(
                  """
                  SELECT new io.statedb.persistence.stack.StackRef(
                      s.id, s.workspaceId, s.orgId, o.name, p.name, s.name, s.version,
                      s.activeUpdateId, s.visibility)
                  FROM Stack s
                  JOIN Project p ON p.id = s.projectId
                  JOIN Organization o ON o.id = s.orgId
                  WHERE o.name = :org
                    AND p.name = :project
                    AND s.name = :stack
                  """,
                  StackRef.class)
              .setParameter("org", org)
              .setParameter("project", project)
              .setParameter("stack", stack)
              .getSingleResult();
      return Optional.of(ref);
    } catch (NoResultException e) {
      return Optional.empty();
    } catch (NonUniqueResultException e) {
      // Unreachable under V001/V002 UNIQUE constraints
      // (organizations.name, projects(org_id,name), stacks(project_id,name)).
      // If it fires, the schema has been tampered with or duplicates were inserted via
      // a path that bypasses the constraints — refuse to guess which row is canonical.
      throw new IllegalStateException(
          "Stack-tree integrity violation: multiple rows match ("
              + org
              + "/"
              + project
              + "/"
              + stack
              + "). UNIQUE constraints on organizations.name, projects(org_id,name),"
              + " stacks(project_id,name) must have been bypassed.",
          e);
    }
  }
}
