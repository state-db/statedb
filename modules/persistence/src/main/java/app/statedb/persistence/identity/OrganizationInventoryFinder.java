package app.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Lists organizations with fixed-query human-member and stack counts. */
@ApplicationScoped
public class OrganizationInventoryFinder {

  @Inject EntityManager em;

  public List<OrganizationInventoryRow> list() {
    List<Organization> organizations = Organization.list("order by name");
    if (organizations.isEmpty()) {
      return List.of();
    }

    Map<UUID, Long> humanMembers =
        counts(
            em.createQuery(
                    """
                    SELECT new app.statedb.persistence.identity.OrganizationAggregateCount(
                        m.orgId, count(m))
                    FROM OrgMember m
                    WHERE m.role IN :humanRoles
                    GROUP BY m.orgId
                    """,
                    OrganizationAggregateCount.class)
                .setParameter("humanRoles", OrgMember.Role.humanRoles())
                .getResultList());
    Map<UUID, Long> stacks =
        counts(
            em.createQuery(
                    """
                    SELECT new app.statedb.persistence.identity.OrganizationAggregateCount(
                        s.orgId, count(s))
                    FROM Stack s
                    GROUP BY s.orgId
                    """,
                    OrganizationAggregateCount.class)
                .getResultList());

    return organizations.stream()
        .map(
            org ->
                new OrganizationInventoryRow(
                    org, humanMembers.getOrDefault(org.id, 0L), stacks.getOrDefault(org.id, 0L)))
        .toList();
  }

  private static Map<UUID, Long> counts(List<OrganizationAggregateCount> rows) {
    return rows.stream()
        .collect(
            Collectors.toMap(
                OrganizationAggregateCount::orgId,
                OrganizationAggregateCount::total,
                (left, right) -> left));
  }

  public record OrganizationInventoryRow(
      Organization organization, long humanMembers, long stacks) {}
}
