package app.statedb.app.auth;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.Authorizer;
import app.statedb.persistence.authz.CedarResource;
import app.statedb.persistence.authz.PrincipalRoleResolver;
import app.statedb.persistence.authz.ResourceName;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.policy.PolicySetCache;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRefResolver;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-org authorization through {@link Authorizer}, driven the way {@code AuthorizeInterceptor}
 * does: resolve the principal's role in the resource's org, then authorize. Covers the role
 * resolver (session vs token), and the managed (per-org) and per-stack (share) grant templates.
 */
@QuarkusTest
class PolicyGrantAuthorizationTest {

  private static final String ACME = "grant-acme";
  private static final String OTHER = "grant-other";
  private static final String PROJECT = "grant-proj";

  @Inject Authorizer authorizer;
  @Inject PrincipalRoleResolver roleResolver;
  @Inject PolicySetCache policySetCache;
  @Inject StackCreator stackCreator;
  @Inject StackRefResolver stackRefResolver;
  @Inject EntityManager em;

  private UUID acmeId;
  private UUID otherId;
  private UUID dualUserId; // viewer in acme, owner in other
  private UUID bothViewerId; // viewer in acme AND other
  private UUID outsiderId; // owner in other only — not a member of acme
  private UUID prodStackId;

  @BeforeEach
  void seed() {
    tx(
        () -> {
          acmeId = persistOrg(ACME).id;
          otherId = persistOrg(OTHER).id;

          dualUserId = user("dual").id;
          member(acmeId, dualUserId, OrgMember.Role.viewer);
          member(otherId, dualUserId, OrgMember.Role.owner);

          bothViewerId = user("both").id;
          member(acmeId, bothViewerId, OrgMember.Role.viewer);
          member(otherId, bothViewerId, OrgMember.Role.viewer);

          outsiderId = user("outsider").id;
          member(otherId, outsiderId, OrgMember.Role.owner);

          stackCreator.create(acmeId, ACME, PROJECT, "prod", Map.of());
          stackCreator.create(acmeId, ACME, PROJECT, "staging", Map.of());
        });
    prodStackId = stackRefResolver.resolve(ACME, PROJECT, "prod").orElseThrow().stackId();
  }

  @AfterEach
  void cleanup() {
    tx(
        () -> {
          // org / user deletes cascade org_members, stacks, and policy_grants.
          em.createNativeQuery("DELETE FROM organizations WHERE name IN (?1, ?2)")
              .setParameter(1, ACME)
              .setParameter(2, OTHER)
              .executeUpdate();
          em.createNativeQuery("DELETE FROM users WHERE iss = ?1")
              .setParameter(1, "local:test")
              .executeUpdate();
        });
    policySetCache.reload();
  }

  @Test
  void sessionRoleIsResolvedPerResourceOrg() {
    AuthenticatedPrincipal dual = session(dualUserId, "dual");
    // viewer in acme: reads, cannot create
    assertTrue(decide(dual, "stack:Read", acmeId, stack(ACME, "prod")));
    assertFalse(decide(dual, "stack:Create", acmeId, orgResource(ACME)));
    // owner in other: can create
    assertTrue(decide(dual, "stack:Create", otherId, orgResource(OTHER)));
  }

  @Test
  void tokenIsScopedToItsOwnOrg() {
    // The user is owner in other, but this token is scoped to acme: it grants nothing in other.
    AuthenticatedPrincipal acmeToken =
        token(dualUserId, "dual", acmeId, ACME, OrgMember.Role.viewer);
    assertFalse(decide(acmeToken, "stack:Create", otherId, orgResource(OTHER)));
    assertTrue(decide(acmeToken, "stack:Read", acmeId, stack(ACME, "prod")));
    assertFalse(decide(acmeToken, "stack:Create", acmeId, orgResource(ACME)));
  }

  @Test
  void managedGrantElevatesWithinThatOrgOnly() {
    AuthenticatedPrincipal both = session(bothViewerId, "both");
    assertFalse(decide(both, "stack:Create", acmeId, orgResource(ACME)));

    grant("StackWriteAccess", acmeId, bothViewerId, null);

    assertTrue(decide(both, "stack:Create", acmeId, orgResource(ACME)));
    // viewer in other, no grant there → still denied
    assertFalse(decide(both, "stack:Create", otherId, orgResource(OTHER)));
  }

  @Test
  void stackShareWriteElevatesOneStackOnly() {
    AuthenticatedPrincipal dual = session(dualUserId, "dual");
    assertFalse(decide(dual, "stack:Lock", acmeId, stack(ACME, "prod")));

    grant("StackWriteResourcePolicy", acmeId, dualUserId, prodStackId);

    assertTrue(decide(dual, "stack:Lock", acmeId, stack(ACME, "prod")));
    assertFalse(decide(dual, "stack:Lock", acmeId, stack(ACME, "staging")));
  }

  @Test
  void stackShareReadDoesNotGrantWrite() {
    AuthenticatedPrincipal dual = session(dualUserId, "dual");
    grant("StackReadResourcePolicy", acmeId, dualUserId, prodStackId);
    assertTrue(decide(dual, "stack:Read", acmeId, stack(ACME, "prod")));
    assertFalse(decide(dual, "stack:Lock", acmeId, stack(ACME, "prod")));
  }

  @Test
  void shareToNonMemberIsNeutralizedByMembershipGuard() {
    // outsider is not a member of acme; a (stale/invalid) share must not grant anything.
    AuthenticatedPrincipal outsider = session(outsiderId, "outsider");
    grant("StackWriteResourcePolicy", acmeId, outsiderId, prodStackId);
    assertFalse(decide(outsider, "stack:Lock", acmeId, stack(ACME, "prod")));
  }

  @Test
  void deletingTheStackCascadesTheShareGrant() {
    AuthenticatedPrincipal dual = session(dualUserId, "dual");
    grant("StackWriteResourcePolicy", acmeId, dualUserId, prodStackId);
    assertTrue(decide(dual, "stack:Lock", acmeId, stack(ACME, "prod")));

    deleteGrantedStack();
    policySetCache.reload();

    assertFalse(decide(dual, "stack:Lock", acmeId, stack(ACME, "prod")));
  }

  // --- helpers ---

  private boolean decide(
      AuthenticatedPrincipal p, String action, UUID resourceOrgId, CedarResource resource) {
    return read(
        () -> {
          OrgMember.Role role = roleResolver.resolve(p, resourceOrgId).orElse(null);
          return authorizer.authorize(p, role, action, resource).allowed();
        });
  }

  private void grant(String templateId, UUID orgId, UUID userId, UUID stackId) {
    tx(
        () -> {
          PolicyGrant g = new PolicyGrant();
          g.templateId = templateId;
          g.orgId = orgId;
          g.principalUserId = userId;
          g.resourceStackId = stackId;
          g.persistAndFlush();
        });
    policySetCache.reload();
  }

  private static AuthenticatedPrincipal session(UUID userId, String sub) {
    return AuthenticatedPrincipal.consoleSession(userId, "local:test", "authz-" + sub);
  }

  private static AuthenticatedPrincipal token(
      UUID userId, String sub, UUID orgId, String orgName, OrgMember.Role role) {
    return new AuthenticatedPrincipal(
        userId,
        "local:test",
        "authz-" + sub,
        orgId,
        orgName,
        role,
        ApiToken.Kind.service_account,
        UUID.randomUUID());
  }

  private static EntityUID uid(String type, String id) {
    return EntityTypeName.parse(type).orElseThrow().of(id);
  }

  // Mirrors StackEntityLoader: resource-name ids + the matching `id` attribute (plus the
  // `visibility` attribute the viewer policy's `when` reads), so resources line up with the
  // name-keyed grant links assembled by PolicySetCache.
  private static CedarResource stack(String org, String name) {
    EntityUID o = uid("Org", ResourceName.org(org));
    EntityUID p = uid("Project", ResourceName.project(org, PROJECT));
    EntityUID s = uid("Stack", ResourceName.stack(org, PROJECT, name));
    Set<Entity> entities =
        Set.of(
            new Entity(o, idAttr(ResourceName.org(org)), Set.of()),
            new Entity(p, idAttr(ResourceName.project(org, PROJECT)), Set.of(o)),
            new Entity(
                s,
                Map.of(
                    "id",
                    new PrimString(ResourceName.stack(org, PROJECT, name)),
                    "visibility",
                    new PrimString(Stack.VISIBILITY_INTERNAL)),
                Set.of(p)));
    return new CedarResource(s, entities);
  }

  private static CedarResource orgResource(String name) {
    EntityUID uid = uid("Org", ResourceName.org(name));
    return new CedarResource(
        uid, Set.of(new Entity(uid, idAttr(ResourceName.org(name)), Set.of())));
  }

  private static Map<String, com.cedarpolicy.value.Value> idAttr(String resourceName) {
    return Map.of("id", new PrimString(resourceName));
  }

  private static Organization persistOrg(String name) {
    Organization o = new Organization();
    o.name = name;
    o.displayName = name;
    o.persistAndFlush();
    return o;
  }

  private static User user(String sub) {
    User u = new User();
    u.iss = "local:test";
    u.sub = "authz-" + sub;
    u.email = sub + "@example.com";
    u.displayName = sub;
    u.persistAndFlush();
    return u;
  }

  private static void member(UUID orgId, UUID userId, OrgMember.Role role) {
    OrgMember m = new OrgMember();
    m.orgId = orgId;
    m.userId = userId;
    m.role = role;
    m.persistAndFlush();
  }

  private void deleteGrantedStack() {
    tx(
        () ->
            em.createNativeQuery("DELETE FROM stacks WHERE id = ?1")
                .setParameter(1, prodStackId)
                .executeUpdate());
  }
}
