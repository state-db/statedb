package io.statedb.persistence.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class ApiTokenFinder {

  @Inject EntityManager em;

  // Membership join is part of the same statement so a token whose org_members row
  // was deleted (user removed from org) returns empty, not a stale principal.
  public Optional<AuthenticatedPrincipal> findValidByHash(String tokenHash) {
    return em.createQuery(
            """
            SELECT new io.statedb.persistence.auth.AuthenticatedPrincipal(
              t.userId, u.iss, u.sub, t.orgId, o.name, m.role, t.kind, t.id)
              FROM ApiToken t, User u, Organization o, OrgMember m
             WHERE t.userId = u.id
               AND t.orgId = o.id
               AND m.orgId = t.orgId
               AND m.userId = t.userId
               AND t.tokenHash = :hash
               AND (t.expiresAt IS NULL OR t.expiresAt > :now)
            """,
            AuthenticatedPrincipal.class)
        .setParameter("hash", tokenHash)
        .setParameter("now", Instant.now())
        .getResultStream()
        .findFirst();
  }
}
