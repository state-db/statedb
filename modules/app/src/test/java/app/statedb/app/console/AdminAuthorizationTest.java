package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.Authorizer;
import app.statedb.persistence.authz.CedarResource;
import app.statedb.persistence.authz.ResourceName;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An env-allow-listed OIDC subject must be a real Cedar admin, not merely {@code siteAdmin:true} in
 * {@code /api/user}. With no org membership (role null), the admin policy still grants the action;
 * a non-listed subject is denied; the bootstrap break-glass identity stays admin.
 */
@QuarkusTest
@TestProfile(AdminAuthorizationTest.AdminProfile.class)
class AdminAuthorizationTest {

  public static final class AdminProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("statedb.admin.subjects", "https://idp.test:admin-sub");
    }
  }

  @Inject Authorizer authorizer;

  @Test
  void allowListedOidcSubjectIsCedarAdmin() {
    AuthenticatedPrincipal admin = session("https://idp.test", "admin-sub");
    AuthenticatedPrincipal nonAdmin = session("https://idp.test", "someone-else");
    // "staging" (not "prod") to avoid the DenyDeleteProtectedStacks guardrail, which forbids even
    // admins from deleting a *prod stack.
    CedarResource stack = stack("any-org", "any-proj", "staging");

    assertTrue(
        authorizer.authorize(admin, null, "stack:Delete", stack).allowed(),
        "allow-listed subject is admin even with no role in the resource's org");
    assertFalse(
        authorizer.authorize(nonAdmin, null, "stack:Delete", stack).allowed(),
        "a non-listed subject with no role is denied");
    CedarResource org = org("any-org");
    assertTrue(authorizer.authorize(admin, null, "org:ManageMembers", org).allowed());
    assertFalse(authorizer.authorize(nonAdmin, null, "org:ManageMembers", org).allowed());
    assertTrue(authorizer.authorize(admin, null, "vcs:Manage", org).allowed());
    assertFalse(authorizer.authorize(nonAdmin, null, "vcs:Manage", org).allowed());
    CedarResource site = site();
    assertTrue(authorizer.authorize(admin, null, "vcs:ManageSite", site).allowed());
    assertFalse(authorizer.authorize(nonAdmin, null, "vcs:ManageSite", site).allowed());
  }

  @Test
  void bootstrapAdminRemainsCedarAdmin() {
    AuthenticatedPrincipal bootstrap = session("local:bootstrap", "admin");
    CedarResource stack = stack("any-org", "any-proj", "staging");
    assertTrue(authorizer.authorize(bootstrap, null, "stack:Delete", stack).allowed());
  }

  private static AuthenticatedPrincipal session(String iss, String sub) {
    return AuthenticatedPrincipal.consoleSession(UUID.randomUUID(), iss, sub);
  }

  private static CedarResource stack(String org, String proj, String name) {
    EntityUID o = uid("Org", ResourceName.org(org));
    EntityUID p = uid("Project", ResourceName.project(org, proj));
    EntityUID s = uid("Stack", ResourceName.stack(org, proj, name));
    Set<Entity> entities =
        Set.of(
            new Entity(o, idAttr(ResourceName.org(org)), Set.of()),
            new Entity(p, idAttr(ResourceName.project(org, proj)), Set.of(o)),
            new Entity(s, idAttr(ResourceName.stack(org, proj, name)), Set.of(p)));
    return new CedarResource(s, entities);
  }

  private static CedarResource org(String name) {
    EntityUID uid = uid("Org", ResourceName.org(name));
    return new CedarResource(
        uid, Set.of(new Entity(uid, idAttr(ResourceName.org(name)), Set.of())));
  }

  private static CedarResource site() {
    EntityUID uid = uid("Site", ResourceName.site());
    return new CedarResource(uid, Set.of(new Entity(uid, idAttr(ResourceName.site()), Set.of())));
  }

  private static EntityUID uid(String type, String id) {
    return EntityTypeName.parse(type).orElseThrow().of(id);
  }

  private static Map<String, Value> idAttr(String resourceName) {
    return Map.of("id", new PrimString(resourceName));
  }
}
