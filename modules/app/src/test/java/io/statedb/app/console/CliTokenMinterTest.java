package io.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.auth.ApiTokenFinder;
import io.statedb.persistence.auth.ApiTokenHasher;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.auth.AuthenticatedPrincipal.CredentialKind;
import io.statedb.persistence.auth.AuthenticatedPrincipal.CredentialScopeKind;
import io.statedb.persistence.auth.CliTokenMinter;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CliTokenMinterTest {

  private static final String ISS = "https://idp.example.test";
  private static final String SUB = "mint-user";
  private static final String ORG = "mint-acme";

  @Inject EntityManager em;

  @Inject CliTokenMinter minter;

  @Inject ApiTokenFinder tokenFinder;

  @Inject ApiTokenHasher hasher;

  private UUID userId;
  private UUID orgId;

  @BeforeEach
  void seed() {
    cleanup();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = ORG;
              org.persistAndFlush();
              orgId = org.id;

              User user = new User();
              user.iss = ISS;
              user.sub = SUB;
              user.email = "mint@example.com";
              user.persistAndFlush();
              userId = user.id;

              OrgMember m = new OrgMember();
              m.orgId = org.id;
              m.userId = user.id;
              m.role = OrgMember.Role.member;
              m.persistAndFlush();
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, ISS)
                  .setParameter(2, SUB)
                  .executeUpdate();
            });
  }

  @Test
  void mintCreatesAnAccountScoped24hOidcTokenResolvableByItsHash() {
    CliTokenMinter.MintedCliToken minted = minter.mint(ISS, SUB, "my laptop");

    assertTrue(
        Duration.between(Instant.now(), minted.expiresAt()).toHours() >= 23,
        "token expires ~24h out");

    ApiToken stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ApiToken.<ApiToken>find("tokenHash", hasher.hash(minted.rawToken()))
                        .firstResult());
    assertEquals(ApiToken.Kind.oidc, stored.kind);
    assertEquals(userId, stored.userId);
    assertEquals("my laptop", stored.description);
    assertNull(stored.orgId, "oidc tokens are account-scoped: org_id is null");

    // The principal carries no org/role; PrincipalRoleResolver resolves the role per request,
    // exactly like a console session.
    Optional<AuthenticatedPrincipal> principal =
        QuarkusTransaction.requiringNew()
            .call(() -> tokenFinder.findValidByHash(hasher.hash(minted.rawToken())));
    assertTrue(principal.isPresent(), "minted token authenticates like any api_token");
    assertEquals(CredentialKind.oidc_cli_token, principal.get().credential().kind());
    assertEquals(
        CredentialScopeKind.all_current_memberships, principal.get().credentialScope().kind());
    assertNull(principal.get().credentialScope().orgId());
    assertNull(principal.get().credentialScope().orgName());
    assertNull(principal.get().credentialScope().role());
  }

  @Test
  void mintBlankDescriptionFallsBackToDefaultLabel() {
    CliTokenMinter.MintedCliToken minted = minter.mint(ISS, SUB, "  ");

    String label =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ApiToken.<ApiToken>find("tokenHash", hasher.hash(minted.rawToken()))
                        .<ApiToken>firstResult()
                        .description);
    assertEquals("CLI token", label);
  }
}
