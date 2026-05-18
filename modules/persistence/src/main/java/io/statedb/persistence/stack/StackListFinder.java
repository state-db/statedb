package io.statedb.persistence.stack;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * Lists {@link StackSummary} rows for a tenant. Backs Pulumi's {@code GET /api/user/stacks} and the
 * console workspace listing; duplicating the JOIN across modules would be a tenant-isolation
 * hazard.
 *
 * <p>{@code join fetch} walks the {@code @ManyToOne} navigation on {@link Stack} to collapse
 * stack/project/organization/workspace into a single SQL statement.
 */
@ApplicationScoped
public class StackListFinder {

  public List<StackSummary> findForOrg(UUID orgId, String projectFilter) {
    String hql =
        "from Stack s "
            + "join fetch s.project p "
            + "join fetch p.organization o "
            + "join fetch s.workspace w "
            + "where s.orgId = ?1 "
            + (projectFilter != null ? "and p.name = ?2 " : "")
            + "order by o.name, p.name, s.name";

    List<Stack> stacks =
        projectFilter != null
            ? Stack.<Stack>find(hql, orgId, projectFilter).list()
            : Stack.<Stack>find(hql, orgId).list();

    return stacks.stream()
        .map(
            s ->
                new StackSummary(
                    s.id,
                    s.orgId,
                    s.project.organization.name,
                    s.project.name,
                    s.name,
                    s.workspace.kind,
                    s.version,
                    null,
                    null))
        .toList();
  }
}
