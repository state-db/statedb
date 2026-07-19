package io.statedb.persistence.identity;

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
            SELECT new io.statedb.persistence.identity.ServiceAccountView(
              u.id, u.displayName, u.createdAt, t.expiresAt, t.lastUsedAt)
              FROM User u
              JOIN OrgMember m ON m.userId = u.id
              LEFT JOIN ApiToken t ON t.userId = u.id AND t.orgId = m.orgId
             WHERE m.orgId = :org AND m.role = :role
             ORDER BY u.createdAt
            """,
            ServiceAccountView.class)
        .setParameter("org", orgId)
        .setParameter("role", OrgMember.Role.service)
        .getResultList();
  }

  /** True iff the account exists as a service identity in the given org. */
  public boolean existsInOrg(UUID orgId, UUID accountId) {
    return OrgMember.count(
            "orgId = ?1 and userId = ?2 and role = ?3", orgId, accountId, OrgMember.Role.service)
        > 0;
  }
}
