package io.statedb.persistence.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class ApiTokenFinder {

  @Inject EntityManager em;

  // One statement authenticates the actor and builds the credential scope:
  //   - service_account -> single_org scope. The OrgMember join is mandatory so revoking membership
  //     makes the token stop authenticating instead of returning a stale role.
  //   - oidc -> all_current_memberships scope. No org is carried on the credential; role is
  //     resolved later from the target resource's org through PrincipalRoleResolver, same as a
  //     console session.
  // The final predicate enforces live membership only for the single-org token case.
  public Optional<AuthenticatedPrincipal> findValidByHash(String tokenHash) {
    return em.createQuery(
            """
            SELECT new io.statedb.persistence.auth.AuthenticatedPrincipal(
              t.userId, u.iss, u.sub, t.orgId, o.name, m.role, t.kind, t.id)
              FROM ApiToken t
              JOIN User u ON u.id = t.userId
              LEFT JOIN Organization o ON o.id = t.orgId
              LEFT JOIN OrgMember m ON m.orgId = t.orgId AND m.userId = t.userId
             WHERE t.tokenHash = :hash
               AND (t.expiresAt IS NULL OR t.expiresAt > :now)
               AND (t.orgId IS NULL OR m.role IS NOT NULL)
            """,
            AuthenticatedPrincipal.class)
        .setParameter("hash", tokenHash)
        .setParameter("now", Instant.now())
        .getResultStream()
        .findFirst();
  }
}
