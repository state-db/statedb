package io.statedb.persistence.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ApiTokenFinder {

  @Inject EntityManager em;

  // Membership join is part of the same statement so a token whose org_members row
  // was deleted (user removed from org) returns empty, not a stale principal.
  public Optional<AuthenticatedPrincipal> findValidByHash(String tokenHash) {
    try {
      Object[] row =
          (Object[])
              em.createNativeQuery(
                      """
                      SELECT t.user_id, t.org_id, o.name, t.kind, t.id
                      FROM api_tokens t
                      JOIN organizations o ON o.id = t.org_id
                      JOIN org_members m
                        ON m.org_id = t.org_id AND m.user_id = t.user_id
                      WHERE t.token_hash = :hash
                        AND (t.expires_at IS NULL OR t.expires_at > now())
                      """)
                  .setParameter("hash", tokenHash)
                  .getSingleResult();
      return Optional.of(
          new AuthenticatedPrincipal(
              (UUID) row[0],
              (UUID) row[1],
              (String) row[2],
              ApiToken.Kind.valueOf((String) row[3]),
              (UUID) row[4]));
    } catch (NoResultException e) {
      return Optional.empty();
    }
  }
}
