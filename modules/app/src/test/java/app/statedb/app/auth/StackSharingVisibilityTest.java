package app.statedb.app.auth;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.authz.Authorizer;
import app.statedb.persistence.authz.CedarResource;
import app.statedb.persistence.authz.PrincipalRoleResolver;
import app.statedb.persistence.authz.ResourceName;
import app.statedb.persistence.authz.StackEntityLoader;
import app.statedb.persistence.authz.StackReadVisibility;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.policy.PolicySetCache;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackListFinder;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.stack.StackRefResolver;
import app.statedb.persistence.stack.StackSummary;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimString;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The guest role + internal/private visibility model end-to-end (design/stack-sharing.md): the
 * role-floor regressions, and the list/PDP equivalence invariant — the {@link StackListFinder} row
 * set and the {@link StackReadVisibility} loader arm must both equal the PDP's per-stack {@code
 * stack:Read} decisions.
 *
 * <p>Seeded matrix in org {@code vis-acme}: an {@code internal} stack ("app") and a {@code private}
 * stack ("secrets"); one user per human role plus a bare guest, an outsider (member of another org
 * only), and a CI service account. Grants: guest → read share on "app"; viewer → write share on
 * "secrets" (write shares carry read); member → no grant (private stays hidden).
 */
@QuarkusTest
class StackSharingVisibilityTest {

  private static final String ACME = "vis-acme";
  private static final String OTHER = "vis-other";
  private static final String PROJECT = "vis-proj";
  private static final String INTERNAL_STACK = "app";
  private static final String PRIVATE_STACK = "secrets";
  private static final String ISS = "local:vistest";

  @Inject Authorizer authorizer;
  @Inject PrincipalRoleResolver roleResolver;
  @Inject PolicySetCache policySetCache;
  @Inject StackCreator stackCreator;
  @Inject StackRefResolver stackRefResolver;
  @Inject StackListFinder stackListFinder;
  @Inject EntityManager em;

  private UUID acmeId;
  private UUID ownerId;
  private UUID memberId;
  private UUID viewerId;
  private UUID guestId;
  private UUID bareGuestId;
  private UUID outsiderId;
  private UUID serviceUserId;
  private UUID internalStackId;
  private UUID privateStackId;

  @BeforeEach
  void seed() {
    tx(
        () -> {
          acmeId = persistOrg(ACME).id;
          UUID otherId = persistOrg(OTHER).id;

          ownerId = user("owner").id;
          member(acmeId, ownerId, OrgMember.Role.owner);
          memberId = user("member").id;
          member(acmeId, memberId, OrgMember.Role.member);
          viewerId = user("viewer").id;
          member(acmeId, viewerId, OrgMember.Role.viewer);
          guestId = user("guest").id;
          member(acmeId, guestId, OrgMember.Role.guest);
          bareGuestId = user("bare-guest").id;
          member(acmeId, bareGuestId, OrgMember.Role.guest);
          outsiderId = user("outsider").id;
          member(otherId, outsiderId, OrgMember.Role.member);
          serviceUserId = user("ci").id;
          member(acmeId, serviceUserId, OrgMember.Role.service);

          stackCreator.create(acmeId, ACME, PROJECT, INTERNAL_STACK, Map.of());
          StackRef prv = stackCreator.create(acmeId, ACME, PROJECT, PRIVATE_STACK, Map.of());
          stackCreator.create(otherId, OTHER, PROJECT, INTERNAL_STACK, Map.of());
          Stack secrets = Stack.findById(prv.stackId());
          secrets.visibility = Stack.VISIBILITY_PRIVATE;
        });
    internalStackId = ref(INTERNAL_STACK).stackId();
    privateStackId = ref(PRIVATE_STACK).stackId();

    grant("StackReadResourcePolicy", guestId, internalStackId);
    grant("StackWriteResourcePolicy", viewerId, privateStackId);
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
              .setParameter(1, ISS)
              .executeUpdate();
        });
    policySetCache.reload();
  }

  @Test
  void roleFloorAcrossVisibilityAndGrants() {
    assertEquals(Set.of(INTERNAL_STACK, PRIVATE_STACK), listedAcmeStacks(ownerId));
    // member without a grant: the private stack is invisible
    assertEquals(Set.of(INTERNAL_STACK), listedAcmeStacks(memberId));
    // viewer's write share on the private stack carries read
    assertEquals(Set.of(INTERNAL_STACK, PRIVATE_STACK), listedAcmeStacks(viewerId));
    // guest sees exactly the granted stack
    assertEquals(Set.of(INTERNAL_STACK), listedAcmeStacks(guestId));
    // bare guest sees nothing
    assertEquals(Set.of(), listedAcmeStacks(bareGuestId));
    assertEquals(Set.of(), listedAcmeStacks(outsiderId));
  }

  @Test
  @TestTransaction
  void targetedLookupPreservesVisibilityTenantAndExactNameSemantics() {
    assertTrue(targetedVisible(ownerId, ACME, PROJECT, INTERNAL_STACK));
    assertTrue(targetedVisible(ownerId, ACME, PROJECT, PRIVATE_STACK));
    assertTrue(targetedVisible(memberId, ACME, PROJECT, INTERNAL_STACK));
    assertFalse(targetedVisible(memberId, ACME, PROJECT, PRIVATE_STACK));
    assertTrue(targetedVisible(viewerId, ACME, PROJECT, PRIVATE_STACK));
    assertTrue(targetedVisible(guestId, ACME, PROJECT, INTERNAL_STACK));
    assertFalse(targetedVisible(bareGuestId, ACME, PROJECT, INTERNAL_STACK));
    assertFalse(targetedVisible(outsiderId, ACME, PROJECT, INTERNAL_STACK));
    assertFalse(targetedVisible(ownerId, OTHER, PROJECT, INTERNAL_STACK));
    assertFalse(targetedVisible(ownerId, ACME, "missing-project", INTERNAL_STACK));
    assertFalse(targetedVisible(ownerId, ACME, PROJECT, "App"));
  }

  // The listing invariant of design/stack-sharing.md: for every (principal, stack), listed == PDP
  // stack:Read == loader predicate. The three arms (SQL, Cedar, in-memory) must never drift.
  @Test
  void listingAndLoaderPredicateMirrorThePdp() {
    Map<String, UUID> humans =
        Map.of(
            "owner", ownerId,
            "member", memberId,
            "viewer", viewerId,
            "guest", guestId,
            "bare-guest", bareGuestId,
            "outsider", outsiderId);
    for (Map.Entry<String, UUID> e : humans.entrySet()) {
      Set<String> listed = listedAcmeStacks(e.getValue());
      for (String stackName : List.of(INTERNAL_STACK, PRIVATE_STACK)) {
        boolean pdp = decide(session(e.getValue(), e.getKey()), "stack:Read", stackName);
        assertEquals(
            pdp, listed.contains(stackName), "list vs PDP: " + e.getKey() + " on " + stackName);
        assertEquals(
            pdp,
            loaderCanRead(e.getValue(), e.getKey(), stackName),
            "loader vs PDP: " + e.getKey() + " on " + stackName);
      }
    }
  }

  @Test
  void writeShareGrantsReadAndLockOnThatStackOnly() {
    AuthenticatedPrincipal viewer = session(viewerId, "viewer");
    assertTrue(decide(viewer, "stack:Read", PRIVATE_STACK));
    assertTrue(decide(viewer, "stack:Lock", PRIVATE_STACK));
    assertFalse(decide(viewer, "stack:Lock", INTERNAL_STACK));
    assertFalse(decideOnOrg(viewer, "stack:Create"));
  }

  @Test
  void guestReadShareDoesNotGrantWrite() {
    AuthenticatedPrincipal guest = session(guestId, "guest");
    assertTrue(decide(guest, "stack:Read", INTERNAL_STACK));
    assertFalse(decide(guest, "stack:Lock", INTERNAL_STACK));
    assertFalse(decide(guest, "stack:Read", PRIVATE_STACK));
    assertFalse(decideOnOrg(guest, "stack:Create"));
  }

  @Test
  void onlyOwnersManageOrganizationMembers() {
    assertTrue(decideOnOrg(session(ownerId, "owner"), "org:ManageMembers"));
    assertFalse(decideOnOrg(session(memberId, "member"), "org:ManageMembers"));
    assertFalse(decideOnOrg(session(serviceUserId, "ci"), "org:ManageMembers"));
  }

  @Test
  void onlyOwnersManageVcsConnections() {
    assertTrue(decideOnOrg(session(ownerId, "owner"), "vcs:Manage"));
    assertFalse(decideOnOrg(session(memberId, "member"), "vcs:Manage"));
    assertFalse(decideOnOrg(session(serviceUserId, "ci"), "vcs:Manage"));
  }

  @Test
  void serviceAccountRetainsAccessToPrivateStacks() {
    AuthenticatedPrincipal ci = serviceToken(serviceUserId, "ci");
    assertTrue(decide(ci, "stack:Read", PRIVATE_STACK));
    Set<String> listed =
        read(
            () ->
                stackListFinder.findVisibleToUserInOrg(serviceUserId, acmeId, null).stream()
                    .map(StackSummary::stackName)
                    .collect(Collectors.toSet()));
    assertEquals(Set.of(INTERNAL_STACK, PRIVATE_STACK), listed);
  }

  @Test
  void revokingGuestMembershipIsImmediatelyInert() {
    assertTrue(decide(session(guestId, "guest"), "stack:Read", INTERNAL_STACK));

    revokeGuestMembership();

    // No policy reload: the template guard and the listing membership check fail per request.
    assertFalse(decide(session(guestId, "guest"), "stack:Read", INTERNAL_STACK));
    assertEquals(Set.of(), listedAcmeStacks(guestId));
  }

  @Test
  void shareToNonMemberStaysInert() {
    grant("StackReadResourcePolicy", outsiderId, internalStackId);
    assertFalse(decide(session(outsiderId, "outsider"), "stack:Read", INTERNAL_STACK));
    assertEquals(Set.of(), listedAcmeStacks(outsiderId));
  }

  // The managed org-wide template guards on viewer, not guest: its ?resource is the Org, so it
  // matches stack:Create where no stack-read 404 gate applies — a guest-inclusive guard would let
  // a guest with a managed grant create stacks.
  @Test
  void managedWriteGrantToGuestIsInert() {
    grant("StackWriteAccess", bareGuestId, null);
    AuthenticatedPrincipal guest = session(bareGuestId, "bare-guest");
    assertFalse(decideOnOrg(guest, "stack:Create"));
    assertFalse(decide(guest, "stack:Lock", INTERNAL_STACK));
    assertEquals(Set.of(), listedAcmeStacks(bareGuestId));

    // The same grant elevates a viewer — the guard, not the template, is what excludes guests.
    grant("StackWriteAccess", viewerId, null);
    assertTrue(decideOnOrg(session(viewerId, "viewer"), "stack:Create"));
  }

  // --- helpers ---

  private StackRef ref(String stackName) {
    return stackRefResolver.resolve(ACME, PROJECT, stackName).orElseThrow();
  }

  private Set<String> listedAcmeStacks(UUID userId) {
    return read(
        () ->
            stackListFinder.findVisibleToUser(userId, null).stream()
                .filter(r -> ACME.equals(r.orgName()))
                .map(StackSummary::stackName)
                .collect(Collectors.toSet()));
  }

  private boolean decide(AuthenticatedPrincipal p, String action, String stackName) {
    return read(
        () -> {
          CedarResource resource = StackEntityLoader.stackResource(ref(stackName));
          OrgMember.Role role = roleResolver.resolve(p, acmeId).orElse(null);
          return authorizer.authorize(p, role, action, resource).allowed();
        });
  }

  private boolean targetedVisible(UUID userId, String org, String project, String stack) {
    return stackListFinder.findVisibleToUser(userId, org, project, stack).isPresent();
  }

  private boolean decideOnOrg(AuthenticatedPrincipal p, String action) {
    return read(
        () -> {
          EntityUID uid = EntityTypeName.parse("Org").orElseThrow().of(ResourceName.org(ACME));
          CedarResource resource =
              new CedarResource(
                  uid,
                  Set.of(
                      new Entity(
                          uid, Map.of("id", new PrimString(ResourceName.org(ACME))), Set.of())));
          OrgMember.Role role = roleResolver.resolve(p, acmeId).orElse(null);
          return authorizer.authorize(p, role, action, resource).allowed();
        });
  }

  private boolean loaderCanRead(UUID userId, String sub, String stackName) {
    return read(
        () -> {
          StackRef r = ref(stackName);
          Optional<OrgMember.Role> role = roleResolver.resolve(session(userId, sub), acmeId);
          return role.isPresent()
              && StackReadVisibility.canRead(
                  role.get(),
                  r.visibility(),
                  () -> PolicyGrant.hasStackReadShare(userId, r.stackId()));
        });
  }

  private void grant(String templateId, UUID userId, UUID stackId) {
    tx(
        () -> {
          PolicyGrant g = new PolicyGrant();
          g.templateId = templateId;
          g.orgId = acmeId;
          g.principalUserId = userId;
          g.resourceStackId = stackId;
          g.persistAndFlush();
        });
    policySetCache.reload();
  }

  private static AuthenticatedPrincipal session(UUID userId, String sub) {
    return AuthenticatedPrincipal.consoleSession(userId, ISS, "vis-" + sub);
  }

  // An org-pinned CI token, the shape TokenAuthFilter produces for kind=service_account.
  private AuthenticatedPrincipal serviceToken(UUID userId, String sub) {
    return new AuthenticatedPrincipal(
        userId,
        ISS,
        "vis-" + sub,
        acmeId,
        ACME,
        OrgMember.Role.service,
        ApiToken.Kind.service_account,
        UUID.randomUUID());
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
    u.iss = ISS;
    u.sub = "vis-" + sub;
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

  private void revokeGuestMembership() {
    tx(() -> OrgMember.delete("orgId = ?1 and userId = ?2", acmeId, guestId));
  }
}
