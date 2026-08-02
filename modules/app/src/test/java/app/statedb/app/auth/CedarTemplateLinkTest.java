package app.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.LinkValue;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.policy.TemplateLink;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Low-level proof of the cedar-java template + link flow that Parts 2/3 build on (no DB / Quarkus).
 * A share template binds {@code ?principal} + {@code ?resource} and guards on org membership
 * ({@code principal in Role::"viewer"}); once linked, only the named member, on the named stack, is
 * permitted — a non-member with the same link is neutralized by the guard.
 */
class CedarTemplateLinkTest {

  private static EntityUID uid(String type, String id) {
    return EntityTypeName.parse(type).orElseThrow().of(id);
  }

  private static Entity actionGroupMember(String action, String group) {
    return new Entity(uid("Action", action), Set.of(uid("Action", group)));
  }

  @Test
  void linkedShareGrantsOnlyTheNamedMemberAndStack() throws Exception {
    Policy template =
        new Policy(
            "permit(principal == ?principal,"
                + " action in Action::\"stack:ReadOnlyAccess\","
                + " resource == ?resource)"
                + " when { principal in Role::\"viewer\" };",
            "stack-share");

    EntityUID member = uid("User", "u-member");
    EntityUID nonMember = uid("User", "u-nonmember");
    EntityUID viewer = uid("Role", "viewer");
    EntityUID stack = uid("Stack", "acme/proj/prod");
    EntityUID otherStack = uid("Stack", "acme/proj/staging");

    TemplateLink linkMember =
        new TemplateLink(
            "stack-share",
            "grant-1",
            List.of(new LinkValue("?principal", member), new LinkValue("?resource", stack)));
    TemplateLink linkNonMember =
        new TemplateLink(
            "stack-share",
            "grant-2",
            List.of(new LinkValue("?principal", nonMember), new LinkValue("?resource", stack)));

    PolicySet policySet =
        new PolicySet(Set.of(), Set.of(template), List.of(linkMember, linkNonMember));

    Set<Entity> entities =
        Set.of(
            new Entity(member, Map.of(), Set.of(viewer)),
            new Entity(nonMember, Map.of(), Set.of()),
            new Entity(viewer),
            new Entity(stack, Map.of(), Set.of()),
            new Entity(otherStack, Map.of(), Set.of()),
            actionGroupMember("stack:Read", "stack:ReadOnlyAccess"),
            new Entity(uid("Action", "stack:ReadOnlyAccess"), Set.of()));

    BasicAuthorizationEngine engine = new BasicAuthorizationEngine();

    assertTrue(allowed(engine, member, "stack:Read", stack, policySet, entities));
    assertFalse(allowed(engine, member, "stack:Read", otherStack, policySet, entities));
    assertFalse(allowed(engine, nonMember, "stack:Read", stack, policySet, entities));
  }

  private static boolean allowed(
      BasicAuthorizationEngine engine,
      EntityUID principal,
      String action,
      EntityUID resource,
      PolicySet policySet,
      Set<Entity> entities)
      throws Exception {
    AuthorizationRequest request =
        new AuthorizationRequest(principal, uid("Action", action), resource, new Context());
    AuthorizationResponse response =
        engine.isAuthorized(request, policySet, new Entities(entities));
    return response.success.orElseThrow().isAllowed();
  }
}
