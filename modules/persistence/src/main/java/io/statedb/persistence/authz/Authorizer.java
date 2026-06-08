package io.statedb.persistence.authz;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.bootstrap.BootstrapProvisioner;
import io.statedb.persistence.policy.PolicySetCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class Authorizer {

  static final EntityUID ADMINS = CedarEntities.euid("Group", "Admins");

  private final AuthorizationEngine engine = new BasicAuthorizationEngine();

  @Inject PolicySetCache policySetCache;

  public AuthorizationDecision authorize(
      AuthenticatedPrincipal principal, String action, CedarResource resource) {
    try {
      Entity principalEntity = principalEntity(principal);
      Set<Entity> all = new HashSet<>();
      all.add(principalEntity);
      all.add(new Entity(ADMINS));
      all.addAll(roleHierarchy(principal.orgName()));
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

  private static Entity principalEntity(AuthenticatedPrincipal principal) {
    Set<EntityUID> parents = new HashSet<>();
    parents.add(CedarEntities.orgRole(principal.orgName(), principal.role().name()));
    if (isAdmin(principal)) {
      parents.add(ADMINS);
    }
    return new Entity(
        CedarEntities.euid("User", principal.iss() + ":" + principal.sub()), Map.of(), parents);
  }

  private static boolean isAdmin(AuthenticatedPrincipal principal) {
    return BootstrapProvisioner.BOOTSTRAP_ISS.equals(principal.iss())
        && BootstrapProvisioner.BOOTSTRAP_SUB.equals(principal.sub());
  }

  static Set<Entity> roleHierarchy(String orgName) {
    EntityUID viewer = CedarEntities.orgRole(orgName, "viewer");
    EntityUID member = CedarEntities.orgRole(orgName, "member");
    EntityUID owner = CedarEntities.orgRole(orgName, "owner");
    return Set.of(
        new Entity(viewer), new Entity(member, Set.of(viewer)), new Entity(owner, Set.of(member)));
  }
}
