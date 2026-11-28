package app.statedb.app;

import static io.quarkus.narayana.jta.QuarkusTransaction.requiringNew;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiTokenHasher;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.lock.Lease;
import app.statedb.persistence.lock.StackLockAcquirer;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.policy.PolicySetCache;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.StackUpdateStarter;
import app.statedb.persistence.update.StartUpdateRequest;
import app.statedb.persistence.update.StartedUpdate;
import app.statedb.testsupport.InMemoryGitHubAppManager;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Committed database fixtures and reset lifecycle for {@link DatabaseTest} subclasses.
 *
 * <p>Every {@code seed*} method commits in its own transaction and refuses to run inside an active
 * transaction so callers cannot accidentally deadlock against {@code requiringNew()} services or
 * hide uncommitted setup from HTTP requests.
 */
@ApplicationScoped
public class TestDb {

  public static final String TEST_ISS = "local:test";

  @Inject EntityManager em;
  @Inject ApiTokenHasher tokenHasher;
  @Inject StackCreator stackCreator;
  @Inject StackUpdateStarter updateStarter;
  @Inject StackLockAcquirer lockAcquirer;
  @Inject PolicySetCache policySetCache;
  @Inject InMemoryGitHubAppManager gitHubAppManager;

  public record SeededOwner(
      UUID orgId,
      UUID userId,
      String orgName,
      String sub,
      String email,
      String displayName,
      String rawToken) {}

  public record SeededStackOwner(
      UUID orgId,
      UUID userId,
      StackRef stack,
      String sub,
      String email,
      String displayName,
      String rawToken) {}

  public record ActiveUpdate(
      UUID stackId, UUID updateId, UUID leaseId, UUID leaseToken, int version) {}

  /**
   * Deletes all test-mutable rows while leaving startup-seeded {@code service_secrets_keyset}
   * intact, then reloads the Cedar policy cache if any policy grants were removed and resets the
   * in-memory GitHub adapter.
   */
  public void reset() {
    boolean hadGrants =
        requiringNew()
            .call(
                () -> {
                  em.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
                  boolean grants = PolicyGrant.count() > 0;
                  em.createNativeQuery("DELETE FROM organizations").executeUpdate();
                  em.createNativeQuery("DELETE FROM users").executeUpdate();
                  em.createNativeQuery("DELETE FROM oidc_providers").executeUpdate();
                  em.createNativeQuery("DELETE FROM vcs_app_registrations").executeUpdate();
                  return grants;
                });
    if (hadGrants) {
      policySetCache.reload();
    }
    gitHubAppManager.reset();
  }

  public UUID seedOrg(String name) {
    return seedOrg(name, name);
  }

  public UUID seedOrg(String name, String displayName) {
    requireNoActiveTransaction();
    return requiringNew().call(() -> persistOrg(name, displayName).id);
  }

  public SeededOwner seedOwner(String orgName, String rawToken) {
    requireNoActiveTransaction();
    return requiringNew()
        .call(
            () -> {
              Organization org = persistOrg(orgName, orgName);
              return persistOwner(org, rawToken);
            });
  }

  public StackRef seedStack(String orgName) {
    return seedStack(orgName, "proj", "dev", Map.of());
  }

  public StackRef seedStack(String orgName, String project, String stack) {
    return seedStack(orgName, project, stack, Map.of());
  }

  public StackRef seedStack(
      String orgName, String project, String stack, Map<String, String> tags) {
    requireNoActiveTransaction();
    return requiringNew()
        .call(
            () -> {
              Organization org = Organization.<Organization>find("name", orgName).firstResult();
              if (org == null) {
                org = persistOrg(orgName, orgName);
              }
              return stackCreator.create(org.id, org.name, project, stack, tags);
            });
  }

  public SeededStackOwner seedOwnerWithStack(
      String orgName, String rawToken, String project, String stack) {
    requireNoActiveTransaction();
    return requiringNew()
        .call(
            () -> {
              Organization org = persistOrg(orgName, orgName);
              SeededOwner owner = persistOwner(org, rawToken);
              StackRef ref = stackCreator.create(org.id, org.name, project, stack, Map.of());
              return new SeededStackOwner(
                  owner.orgId(),
                  owner.userId(),
                  ref,
                  owner.sub(),
                  owner.email(),
                  owner.displayName(),
                  owner.rawToken());
            });
  }

  public ActiveUpdate seedActiveUpdate(UUID stackId, StackUpdate.Kind kind) {
    requireNoActiveTransaction();
    StartedUpdate started =
        updateStarter.start(
            stackId,
            kind,
            null,
            null,
            UUID.randomUUID().toString(),
            new StartUpdateRequest(
                Map.of(), Map.of(), Map.of(), "test", "yaml", "v3.242.0", "test"));
    Lease lease =
        lockAcquirer
            .acquire(stackId, started.updateId(), "user:test", 1, null)
            .orElseThrow(() -> new IllegalStateException("lease not acquired for " + stackId));
    return new ActiveUpdate(
        stackId, started.updateId(), lease.leaseId(), lease.plaintextToken(), started.version());
  }

  private Organization persistOrg(String name, String displayName) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = displayName;
    org.persistAndFlush();
    return org;
  }

  private SeededOwner persistOwner(Organization org, String rawToken) {
    String sub = org.name;
    String email = org.name + "@example.com";
    String displayName = org.name + " Owner";

    User user = new User();
    user.iss = TEST_ISS;
    user.sub = sub;
    user.email = email;
    user.displayName = displayName;
    user.persistAndFlush();

    OrgMember member = new OrgMember();
    member.orgId = org.id;
    member.userId = user.id;
    member.role = OrgMember.Role.owner;
    member.persistAndFlush();

    ApiToken token = new ApiToken();
    token.tokenHash = tokenHasher.hash(rawToken);
    token.userId = user.id;
    token.orgId = org.id;
    token.kind = ApiToken.Kind.service_account;
    token.expiresAt = Instant.now().plusSeconds(3600);
    token.description = org.name;
    token.persistAndFlush();

    return new SeededOwner(org.id, user.id, org.name, sub, email, displayName, rawToken);
  }

  private static void requireNoActiveTransaction() {
    if (QuarkusTransaction.isActive()) {
      throw new IllegalStateException(
          "TestDb.seed* commits on its own; call it outside an active transaction");
    }
  }
}
