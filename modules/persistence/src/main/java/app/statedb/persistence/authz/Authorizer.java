package app.statedb.persistence.authz;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.identity.AdminAllowList;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.policy.PolicySetCache;
import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class Authorizer {

  static final EntityUID ADMIN = CedarEntities.role("admin");
  static final EntityUID SERVICE_STACK_LIFECYCLE = CedarEntities.role("service_stack_lifecycle");

  private static final Set<Entity> ROLE_HIERARCHY = roleHierarchy();

  private final AuthorizationEngine engine = new BasicAuthorizationEngine();

  @Inject PolicySetCache policySetCache;

  @Inject AdminAllowList adminAllowList;

  public AuthorizationDecision authorize(
      AuthenticatedPrincipal principal,
      OrgMember.Role role,
      String action,
      CedarResource resource) {
    try {
      Entity principalEntity = principalEntity(principal, role);
      Set<Entity> all = new HashSet<>();
      all.add(principalEntity);
      all.addAll(ROLE_HIERARCHY);
      all.addAll(policySetCache.actionEntities());
      all.addAll(resource.entities());

      AuthorizationRequest request =
          new AuthorizationRequest(
              principalEntity.getEUID(),
              CedarEntities.euid("Action", action),
              resource.resourceUid(),
              new Context());
      AuthorizationResponse response =
          engine.isAuthorized(request, policySetCache.policySet(), new Entities(all));
      AuthorizationSuccessResponse success =
          response.success.orElseThrow(() -> new IllegalStateException(response.toString()));
      return new AuthorizationDecision(success.isAllowed(), success.getReason());
    } catch (Exception e) {
      throw new IllegalStateException("Cedar authorization failed", e);
    }
  }

  // The principal's role is the one resolved for THIS resource's org (or none, e.g. for an admin
  // acting outside their memberships). The User uid is the global users.id so it matches
  // policy_grants.principal_user_id; no attributes are needed.
  private Entity principalEntity(AuthenticatedPrincipal principal, OrgMember.Role role) {
    Set<EntityUID> parents = new HashSet<>();
    if (role != null) {
      parents.add(CedarEntities.role(role.name()));
    }
    if (role == OrgMember.Role.service
        && principal.credential().kind()
            == AuthenticatedPrincipal.CredentialKind.service_account_token
        && principal.credential().accessProfile() == ApiToken.AccessProfile.stack_lifecycle) {
      parents.add(SERVICE_STACK_LIFECYCLE);
    }
    if (adminAllowList.isAdmin(principal.iss(), principal.sub())) {
      parents.add(ADMIN);
    }
    return new Entity(CedarEntities.user(principal.userId()), Map.of(), parents);
  }

  static Set<Entity> roleHierarchy() {
    // `guest` is the base of the human ladder: `in Role::"guest"` means "any human member,
    // including guests" (the share-template guard), while `in Role::"viewer"` still excludes
    // guests — a bare guest matches no static policy.
    EntityUID guest = CedarEntities.role("guest");
    EntityUID viewer = CedarEntities.role("viewer");
    EntityUID member = CedarEntities.role("member");
    EntityUID owner = CedarEntities.role("owner");
    // `service` stands alone, like admin: CI grants come only from service.cedar, never from the
    // human role ladder — and share grants naming a service account are inert (guard fails).
    return Set.of(
        new Entity(guest),
        new Entity(viewer, Set.of(guest)),
        new Entity(member, Set.of(viewer)),
        new Entity(owner, Set.of(member)),
        new Entity(CedarEntities.role("service")),
        new Entity(SERVICE_STACK_LIFECYCLE, Set.of(CedarEntities.role("service"))),
        new Entity(ADMIN));
  }
}
