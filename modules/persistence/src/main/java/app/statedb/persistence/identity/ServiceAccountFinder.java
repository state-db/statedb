package app.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;

/**
 * Lists an org's service accounts (machine users holding the {@code service} role).
 *
 * <p>Uses a bare {@code EntityManager}: the listing is a constructor projection across users ×
 * org_members × api_tokens joined with {@code ON} clauses, which Panache's {@code project()} cannot
 * express (it rejects queries that already carry a {@code select new} clause), and a read-only
 * listing has no need to hydrate three entity types.
 */
@ApplicationScoped
public class ServiceAccountFinder {

  @Inject EntityManager em;

  public List<ServiceAccountView> forOrg(UUID orgId) {
    return em.createQuery(
            """
            SELECT new app.statedb.persistence.identity.ServiceAccountView(
              u.id, t.id, u.displayName, o.name, o.displayName, t.createdAt,
              t.expiresAt, t.lastUsedAt, t.revokedAt, u.deactivatedAt,
              t.createdByUserId, creator.displayName, creator.email,
              t.revokedByUserId, revoker.displayName, revoker.email)
              FROM User u
              JOIN OrgMember m ON m.userId = u.id
              JOIN Organization o ON o.id = m.orgId
              JOIN ApiToken t ON t.userId = u.id AND t.orgId = m.orgId
              LEFT JOIN User creator ON creator.id = t.createdByUserId
              LEFT JOIN User revoker ON revoker.id = t.revokedByUserId
             WHERE m.orgId = :org AND m.role = :role
             ORDER BY t.createdAt DESC
            """,
            ServiceAccountView.class)
        .setParameter("org", orgId)
        .setParameter("role", OrgMember.Role.service)
        .getResultList();
  }

  /** Lists every service account the actor may manage: owned orgs, or all orgs for site admins. */
  public List<ServiceAccountView> manageableBy(UUID actorUserId, boolean siteAdmin) {
    String managerJoin =
        siteAdmin
            ? ""
            : "JOIN OrgMember manager ON manager.orgId = m.orgId"
                + " AND manager.userId = :actor AND manager.role = :owner";
    var query =
        em.createQuery(
                """
            SELECT new app.statedb.persistence.identity.ServiceAccountView(
              u.id, t.id, u.displayName, o.name, o.displayName, t.createdAt,
              t.expiresAt, t.lastUsedAt, t.revokedAt, u.deactivatedAt,
              t.createdByUserId, creator.displayName, creator.email,
              t.revokedByUserId, revoker.displayName, revoker.email)
              FROM User u
              JOIN OrgMember m ON m.userId = u.id
              JOIN Organization o ON o.id = m.orgId
              JOIN ApiToken t ON t.userId = u.id AND t.orgId = m.orgId
              LEFT JOIN User creator ON creator.id = t.createdByUserId
              LEFT JOIN User revoker ON revoker.id = t.revokedByUserId
            """
                    + managerJoin
                    + " WHERE m.role = :service ORDER BY t.createdAt DESC",
                ServiceAccountView.class)
            .setParameter("service", OrgMember.Role.service);
    if (!siteAdmin) {
      query.setParameter("actor", actorUserId).setParameter("owner", OrgMember.Role.owner);
    }
    return query.getResultList();
  }

  /** True iff the account exists as a service identity in the given org. */
  public boolean existsInOrg(UUID orgId, UUID accountId) {
    return OrgMember.count(
            "orgId = ?1 and userId = ?2 and role = ?3", orgId, accountId, OrgMember.Role.service)
        > 0;
  }
}
