package io.statedb.app.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.ApiToken;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Schema-contract tests for the identity tables. These pin the promises that callers above this
 * layer (TokenAuthFilter, Cedar entity loaders, admin handlers) rely on. They map to the design
 * doc — see {@code design/db-schema.md} §Correctness Invariants — and exist to fail loudly if a
 * future migration weakens an FK / UNIQUE / CHECK / CASCADE that the rest of the codebase assumes.
 *
 * <p>Each negative case is a separate {@code @Test} because a constraint violation marks the JTA
 * transaction rollback-only; further DB work in the same method would fail for unrelated reasons.
 */
@QuarkusTest
class IdentityConstraintsTest {

  // -----------------------------------------------------------------------------------------
  // Foreign keys
  // -----------------------------------------------------------------------------------------

  @Test
  @TestTransaction
  void apiTokenRequiresExistingOrg() {
    User user = newUser();
    user.persistAndFlush();

    ApiToken token = new ApiToken();
    token.tokenHash = "sha256:" + "a".repeat(64);
    token.userId = user.id;
    token.orgId = UUID.randomUUID();
    token.kind = ApiToken.Kind.oidc;

    assertThrows(Exception.class, token::persistAndFlush);
  }

  @Test
  @TestTransaction
  void apiTokenRequiresExistingUser() {
    Organization org = newOrg("acme-fk-user");
    org.persistAndFlush();

    ApiToken token = new ApiToken();
    token.tokenHash = "sha256:" + "b".repeat(64);
    token.userId = UUID.randomUUID();
    token.orgId = org.id;
    token.kind = ApiToken.Kind.oidc;

    assertThrows(Exception.class, token::persistAndFlush);
  }

  @Test
  @TestTransaction
  void orgMemberRequiresExistingOrgAndUser() {
    OrgMember member = new OrgMember();
    member.orgId = UUID.randomUUID();
    member.userId = UUID.randomUUID();
    member.role = OrgMember.Role.owner;

    assertThrows(Exception.class, member::persistAndFlush);
  }

  // -----------------------------------------------------------------------------------------
  // Uniqueness
  // -----------------------------------------------------------------------------------------

  @Test
  @TestTransaction
  void usersIssSubIsUnique() {
    User a = newUser();
    a.persistAndFlush();

    User dup = new User();
    dup.iss = a.iss;
    dup.sub = a.sub;
    dup.email = "different@example.com";

    assertThrows(Exception.class, dup::persistAndFlush);
  }

  @Test
  @TestTransaction
  void apiTokenHashIsUnique() {
    Organization org = newOrg("acme-tok-uniq");
    org.persistAndFlush();
    User user = newUser();
    user.persistAndFlush();

    String sharedHash = "sha256:" + "c".repeat(64);
    ApiToken first = new ApiToken();
    first.tokenHash = sharedHash;
    first.userId = user.id;
    first.orgId = org.id;
    first.kind = ApiToken.Kind.oidc;
    first.persistAndFlush();

    ApiToken collision = new ApiToken();
    collision.tokenHash = sharedHash;
    collision.userId = user.id;
    collision.orgId = org.id;
    collision.kind = ApiToken.Kind.service_account;

    assertThrows(Exception.class, collision::persistAndFlush);
  }

  // -----------------------------------------------------------------------------------------
  // CHECK constraints
  //
  // Java's enum types make it impossible for application code to write an unknown value, so
  // these tests bypass the entity layer and INSERT raw SQL — the goal is to verify the DB-side
  // CHECK is still in place after a future migration, not that the Java mapping forbids it.
  // -----------------------------------------------------------------------------------------

  @Test
  @TestTransaction
  void apiTokenKindIsConstrainedToKnownValues() {
    Organization org = newOrg("acme-kind-check");
    org.persistAndFlush();
    User user = newUser();
    user.persistAndFlush();

    assertThrows(
        Exception.class,
        () ->
            Panache.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO api_tokens (id, token_hash, user_id, org_id, kind)"
                        + " VALUES (?, ?, ?, ?, 'wildcat')")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, "sha256:" + "d".repeat(64))
                .setParameter(3, user.id)
                .setParameter(4, org.id)
                .executeUpdate());
  }

  @Test
  @TestTransaction
  void orgMemberRoleIsConstrainedToKnownValues() {
    Organization org = newOrg("acme-role-check");
    org.persistAndFlush();
    User user = newUser();
    user.persistAndFlush();

    assertThrows(
        Exception.class,
        () ->
            Panache.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO org_members (org_id, user_id, role) VALUES (?, ?, 'emperor')")
                .setParameter(1, org.id)
                .setParameter(2, user.id)
                .executeUpdate());
  }

  // -----------------------------------------------------------------------------------------
  // Cascade behaviour
  // -----------------------------------------------------------------------------------------

  @Test
  @TestTransaction
  void deletingOrgCascadesToTokensAndMembersButLeavesUsers() {
    Organization org = newOrg("acme-cascade-org");
    org.persistAndFlush();
    User user = newUser();
    user.persistAndFlush();
    persistMembership(org.id, user.id, OrgMember.Role.owner);
    persistToken(org.id, user.id, "e");

    Organization.deleteById(org.id);
    Organization.flush();

    assertEquals(0, ApiToken.count("orgId", org.id), "tokens should cascade with the org");
    assertEquals(
        0, OrgMember.count("orgId", org.id), "membership rows should cascade with the org");
    assertEquals(1, User.count("id", user.id), "user must outlive the org they were a member of");
  }

  @Test
  @TestTransaction
  void deletingUserCascadesToTokensAndMembersButLeavesOrg() {
    Organization org = newOrg("acme-cascade-user");
    org.persistAndFlush();
    User user = newUser();
    user.persistAndFlush();
    persistMembership(org.id, user.id, OrgMember.Role.owner);
    persistToken(org.id, user.id, "f");

    User.deleteById(user.id);
    User.flush();

    assertEquals(0, ApiToken.count("userId", user.id), "tokens should cascade with the user");
    assertEquals(
        0, OrgMember.count("userId", user.id), "membership rows should cascade with the user");
    assertEquals(1, Organization.count("id", org.id), "org must outlive a deleted user");
  }

  /**
   * Per {@code design/db-schema.md} §Application invariants #18: removing a user from an org must
   * invalidate their tokens for that org "without deleting the row." The token row stays;
   * authorization code is responsible for re-checking {@code org_members} on every request. This
   * test guards against someone "helpfully" adding {@code ON DELETE CASCADE} from
   * {@code org_members} → {@code api_tokens} in a future migration, which would break the design.
   */
  @Test
  @TestTransaction
  void removingMembershipDoesNotDeleteTokens() {
    Organization org = newOrg("acme-membership-only");
    org.persistAndFlush();
    User user = newUser();
    user.persistAndFlush();
    persistMembership(org.id, user.id, OrgMember.Role.member);
    UUID tokenId = persistToken(org.id, user.id, "g");

    long deleted = OrgMember.delete("orgId = ?1 and userId = ?2", org.id, user.id);
    assertEquals(1, deleted);
    OrgMember.flush();

    assertEquals(1, ApiToken.count("id", tokenId), "token row must survive membership removal");
  }

  // -----------------------------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------------------------

  private static Organization newOrg(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    return org;
  }

  private static User newUser() {
    User u = new User();
    u.iss = "https://accounts.google.com";
    u.sub = "test-" + UUID.randomUUID();
    u.email = "test@example.com";
    return u;
  }

  private static void persistMembership(UUID orgId, UUID userId, OrgMember.Role role) {
    OrgMember m = new OrgMember();
    m.orgId = orgId;
    m.userId = userId;
    m.role = role;
    m.persistAndFlush();
  }

  private static UUID persistToken(UUID orgId, UUID userId, String hashSeed) {
    ApiToken t = new ApiToken();
    t.tokenHash = "sha256:" + hashSeed.repeat(64);
    t.userId = userId;
    t.orgId = orgId;
    t.kind = ApiToken.Kind.oidc;
    t.persistAndFlush();
    assertNotNull(t.id);
    return t.id;
  }
}
