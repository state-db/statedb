package io.statedb.persistence.authz;

import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.identity.OrgMember;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the role a principal holds <b>in the resource's org</b> — the single tenancy seam.
 *
 * <p>A user is a global identity who may belong to several orgs with a different role in each, so
 * "the user's role" is only meaningful relative to a specific org. Every authorization decision is
 * about one resource in one org, so we resolve exactly the role for that org and attach it to the
 * Cedar principal; the static role policies then stay pure RBAC with no org clause.
 *
 * <ul>
 *   <li><b>Token principal</b> (CLI; {@code orgId != null}) is scoped to one org. Its role applies
 *       only there — a token grants nothing outside its org even if the user is a member elsewhere.
 *   <li><b>Session principal</b> (console; {@code orgId == null}) is org-agnostic; the role is
 *       looked up from {@code org_members} for the resource's org.
 * </ul>
 */
@ApplicationScoped
public class PrincipalRoleResolver {

  public Optional<OrgMember.Role> resolve(AuthenticatedPrincipal principal, UUID resourceOrgId) {
    if (resourceOrgId == null) {
      return Optional.empty();
    }
    if (principal.orgId() != null) {
      return resourceOrgId.equals(principal.orgId())
          ? Optional.ofNullable(principal.role())
          : Optional.empty();
    }
    return OrgMember.findRole(resourceOrgId, principal.userId());
  }
}
