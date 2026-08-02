package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.identity.OrgMember;
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
 *
 * <p><b>Deferred — multi-stack operations that span two orgs.</b> Some CLI commands are client-side
 * compositions over several single-stack requests: {@code pulumi state move} exports both stacks
 * and imports both, and nothing on the wire ties those requests to each other (there is no move
 * API, and {@code POST .../import} names only its own stack). Each request is therefore authorized
 * here in isolation. A single-org credential stops a cross-org move at the second stack; an
 * all-current-memberships credential completes it whenever the user belongs to both orgs, since
 * every individual request is one they are entitled to make. Refusing that case needs a decision
 * spanning two orgs, which this resolver deliberately cannot express — revisit here if cross-org
 * moves should be refused rather than authorized twice.
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
