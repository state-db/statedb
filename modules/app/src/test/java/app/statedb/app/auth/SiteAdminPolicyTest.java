package app.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.authz.ResourceName;
import app.statedb.persistence.policy.PolicySetCache;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SiteAdminPolicyTest {

  @Inject PolicySetCache policySetCache;

  @Test
  void siteAdministrationCannotBeDelegatedByAnAdditionalPermit() throws Exception {
    EntityUID admin = uid("User", "admin");
    EntityUID nonAdmin = uid("User", "non-admin");
    EntityUID adminRole = uid("Role", "admin");
    EntityUID action = uid("Action", "provider:Manage");
    EntityUID site = uid("Site", ResourceName.site());

    Set<Policy> policies = new HashSet<>(policySetCache.policySet().policies);
    policies.add(
        new Policy(
            "permit(principal == User::\"non-admin\","
                + " action == Action::\"provider:Manage\","
                + " resource == Site::\""
                + ResourceName.site()
                + "\");",
            "attempted-delegation"));
    PolicySet policySet = new PolicySet(policies, Set.of(), List.of());

    Set<Entity> entities = new HashSet<>(policySetCache.actionEntities());
    entities.add(new Entity(admin, Map.of(), Set.of(adminRole)));
    entities.add(new Entity(nonAdmin));
    entities.add(new Entity(adminRole));
    entities.add(new Entity(site, Map.of("id", new PrimString(ResourceName.site())), Set.of()));

    assertTrue(allowed(admin, action, site, policySet, entities));
    assertFalse(allowed(nonAdmin, action, site, policySet, entities));
  }

  private static boolean allowed(
      EntityUID principal,
      EntityUID action,
      EntityUID resource,
      PolicySet policySet,
      Set<Entity> entities)
      throws Exception {
    return new BasicAuthorizationEngine()
        .isAuthorized(
            new AuthorizationRequest(principal, action, resource, new Context()),
            policySet,
            new Entities(entities))
        .success
        .orElseThrow()
        .isAllowed();
  }

  private static EntityUID uid(String type, String id) {
    return EntityTypeName.parse(type).orElseThrow().of(id);
  }
}
