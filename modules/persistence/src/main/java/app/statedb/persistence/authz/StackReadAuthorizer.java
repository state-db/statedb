package app.statedb.persistence.authz;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.StackRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Applies the stack read-visibility boundary after a route has resolved a {@link StackRef}.
 *
 * <p>Routes may locate a stack by its name triple, an update id, or another child resource. The
 * lookup shape must not grow its own copy of this decision: all of them collapse a stack the
 * principal cannot read to an empty result, then authorize against the same Cedar resource.
 */
@ApplicationScoped
public class StackReadAuthorizer {

  @Inject PrincipalRoleResolver roleResolver;

  @Inject AdminAllowList adminAllowList;

  public Optional<LoadedResource> visibleResource(StackRef ref, AuthenticatedPrincipal principal) {
    Optional<OrgMember.Role> role = roleResolver.resolve(principal, ref.orgId());
    boolean admin = adminAllowList.isAdmin(principal.iss(), principal.sub());
    if (!admin
        && (role.isEmpty()
            || !StackReadVisibility.canRead(
                role.get(),
                ref.visibility(),
                () -> PolicyGrant.hasStackReadShare(principal.userId(), ref.stackId())))) {
      return Optional.empty();
    }
    return Optional.of(
        new LoadedResource(StackEntityLoader.stackResource(ref), role.orElse(null), ref));
  }
}
