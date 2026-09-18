package app.statedb.app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiTokenFinder;
import app.statedb.persistence.auth.AuthenticatedPrincipal;
import app.statedb.persistence.auth.AuthenticatedPrincipal.CredentialKind;
import app.statedb.persistence.auth.AuthenticatedPrincipal.CredentialScopeKind;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SQL-contract tests; HTTP-level coverage lives in {@code TokenAuthFilterTest}. */
@QuarkusTest
class ApiTokenFinderTest {

  @Inject ApiTokenFinder finder;

  @Test
  @TestTransaction
  void activeTokenWithMembershipResolvesToPrincipal() {
    Fixture f = seed("finder-active");
    f.persistToken(Instant.now().plus(7, ChronoUnit.DAYS));

    Optional<AuthenticatedPrincipal> resolved = finder.findValidByHash(f.tokenHash);

    assertTrue(resolved.isPresent(), "active token must resolve");
    AuthenticatedPrincipal p = resolved.get();
    assertEquals(f.user.id, p.userId());
    assertEquals(CredentialKind.service_account_token, p.credential().kind());
    assertEquals(CredentialScopeKind.single_org, p.credentialScope().kind());
    assertEquals(f.org.id, p.credentialScope().orgId());
    assertEquals(f.org.name, p.credentialScope().orgName());
    assertEquals(f.tokenId(), p.apiTokenId());
    assertEquals(ApiToken.AccessProfile.deploy, p.credential().accessProfile());
  }

  @Test
  @TestTransaction
  void expiredTokenDoesNotResolve() {
    Fixture f = seed("finder-expired");
    f.persistToken(Instant.now().minus(1, ChronoUnit.MINUTES));

    assertEquals(Optional.empty(), finder.findValidByHash(f.tokenHash));
  }

  @Test
  @TestTransaction
  void unknownHashReturnsEmpty() {
    assertEquals(Optional.empty(), finder.findValidByHash("0".repeat(64)));
  }

  @Test
  @TestTransaction
  void tokenWithoutMembershipDoesNotResolve() {
    Fixture f = seed("finder-no-member");
    f.persistToken(Instant.now().plus(1, ChronoUnit.DAYS));
    OrgMember.delete("orgId = ?1 and userId = ?2", f.org.id, f.user.id);
    OrgMember.flush();

    assertEquals(Optional.empty(), finder.findValidByHash(f.tokenHash));
  }

  private Fixture seed(String tag) {
    Organization org = new Organization();
    org.name = tag;
    org.displayName = tag;
    org.persistAndFlush();

    User user = new User();
    user.iss = "local:test";
    user.sub = tag;
    user.email = tag + "@example.com";
    user.displayName = tag;
    user.persistAndFlush();

    OrgMember m = new OrgMember();
    m.orgId = org.id;
    m.userId = user.id;
    m.role = OrgMember.Role.member;
    m.persistAndFlush();

    return new Fixture(org, user, tag + "-hash-" + "a".repeat(50));
  }

  private static final class Fixture {
    final Organization org;
    final User user;
    final String tokenHash;
    ApiToken token;

    Fixture(Organization org, User user, String tokenHash) {
      this.org = org;
      this.user = user;
      this.tokenHash = tokenHash;
    }

    void persistToken(Instant expiresAt) {
      ApiToken t = new ApiToken();
      t.tokenHash = tokenHash;
      t.userId = user.id;
      t.orgId = org.id;
      t.kind = ApiToken.Kind.service_account;
      t.description = "finder-test";
      t.expiresAt = expiresAt;
      t.persistAndFlush();
      this.token = t;
    }

    java.util.UUID tokenId() {
      return token.id;
    }
  }
}
