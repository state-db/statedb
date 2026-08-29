package app.statedb.persistence.policy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;

/** Batch-loads stack-scoped grants for members of one organization. */
@ApplicationScoped
public class MemberStackGrantFinder {

  @Inject EntityManager em;

  public List<MemberStackGrantView> forUsers(UUID orgId, List<UUID> userIds) {
    if (userIds.isEmpty()) {
      return List.of();
    }

    // Panache projections cannot express this cross-entity constructor query without mapped
    // associations on PolicyGrant. Keep the tenant joins explicit instead of adding read-only ORM
    // navigation solely for this console view.
    return em.createQuery(
            """
            SELECT new app.statedb.persistence.policy.MemberStackGrantView(
                g.principalUserId, s.id, p.name, s.name, g.templateId, g.createdAt)
            FROM PolicyGrant g
            JOIN Stack s ON s.id = g.resourceStackId AND s.orgId = g.orgId
            JOIN Project p ON p.id = s.projectId AND p.orgId = s.orgId
            WHERE g.orgId = :orgId
              AND g.principalUserId IN :userIds
              AND g.resourceStackId IS NOT NULL
            ORDER BY g.principalUserId, g.createdAt, g.id
            """,
            MemberStackGrantView.class)
        .setParameter("orgId", orgId)
        .setParameter("userIds", userIds)
        .getResultList();
  }
}
