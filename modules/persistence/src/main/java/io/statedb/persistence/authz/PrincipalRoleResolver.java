package io.statedb.persistence.authz;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.identity.OrgMember;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the role a principal holds <b>in the resource's org</b> — the single tenancy boundary.
 *
 * <p>A user is a global identity who may belong to several orgs with a different role in each, so
 * "the user's role" is only meaningful relative to a specific org. Every authorization decision is
 * about one resource in one org, so we resolve exactly the role for that org and attach it to the
 * Cedar principal; the static role policies then stay pure RBAC with no org clause.
 *
 * <ul>
 *   <li><b>Single-org credential scope</b> applies only in its org — it grants nothing outside it
 *       even if the actor is a member elsewhere.
 *   <li><b>All-current-memberships credential scope</b> is org-agnostic; the role is looked up from
 *       {@code org_members} for the resource's org, so membership is re-checked on every request.
 * </ul>
 */
@ApplicationScoped
public class PrincipalRoleResolver {

  public Optional<OrgMember.Role> resolve(AuthenticatedPrincipal principal, UUID resourceOrgId) {
    if (resourceOrgId == null) {
      return Optional.empty();
    }
    AuthenticatedPrincipal.CredentialScope scope = principal.credentialScope();
    if (!scope.includes(resourceOrgId)) {
      return Optional.empty();
    }
    if (scope.resolvesRoleFromMemberships()) {
      return OrgMember.findRole(resourceOrgId, principal.userId());
    }
    return Optional.ofNullable(scope.role());
  }
}
