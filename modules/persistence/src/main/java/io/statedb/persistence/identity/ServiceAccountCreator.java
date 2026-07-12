package io.statedb.persistence.identity;

import io.statedb.persistence.RequestValidationException;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.auth.ApiTokenHasher;
import io.statedb.persistence.auth.RawApiTokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Mints a CI machine identity in one transaction: a machine {@code users} row (reserved issuer
 * {@code local:service}, random subject), an {@code org_members} row with the {@code service} role,
 * and an org-pinned {@code kind=service_account} token.
 *
 * <p>The account is a first-class identity, not a pointer at the human who minted it — the human
 * leaving the org does not break CI. What the token can do is fixed by the service role's own grant
 * set ({@code policies/service.cedar}); the credential's {@code single_org} scope pins it to this
 * org, and the membership join in {@code ApiTokenFinder} means deleting the account (or just its
 * membership) stops the token from even authenticating.
 */
@ApplicationScoped
public class ServiceAccountCreator {

  /** Reserved issuer namespace for machine identities; no OIDC provider can collide with it. */
  public static final String SERVICE_ISS = "local:service";

  static final Duration DEFAULT_TTL = Duration.ofDays(365);

  private static final Duration MAX_TTL = Duration.ofDays(3650);

  @Inject ApiTokenHasher hasher;

  /** The raw token to display once, plus the account it belongs to. */
  public record MintedServiceAccount(
      UUID accountId, String name, String rawToken, Instant expiresAt) {}

  /**
   * @param expiresInDays token lifetime; null → 365 days
   * @throws RequestValidationException (400) on a blank name or out-of-range lifetime
   */
  @Transactional
  public MintedServiceAccount create(UUID orgId, String name, Integer expiresInDays) {
    if (name == null || name.isBlank() || name.strip().length() > 200) {
      throw new RequestValidationException("service account name must be 1-200 characters");
    }
    Duration ttl = expiresInDays == null ? DEFAULT_TTL : Duration.ofDays(expiresInDays);
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
      throw new RequestValidationException("expiresInDays must be between 1 and 3650");
    }

    User account = new User();
    account.iss = SERVICE_ISS;
    account.sub = UUID.randomUUID().toString();
    account.displayName = name.strip();
    account.persist();

    OrgMember membership = new OrgMember();
    membership.orgId = orgId;
    membership.userId = account.id;
    membership.role = OrgMember.Role.service;
    membership.persist();

    String rawToken = RawApiTokens.generate();
    Instant expiresAt = Instant.now().plus(ttl);

    ApiToken token = new ApiToken();
    token.tokenHash = hasher.hash(rawToken);
    token.description = name.strip();
    token.userId = account.id;
    token.orgId = orgId; // single_org credential scope: worth nothing outside this org
    token.kind = ApiToken.Kind.service_account;
    token.expiresAt = expiresAt;
    token.persistAndFlush();

    return new MintedServiceAccount(account.id, account.displayName, rawToken, expiresAt);
  }
}
