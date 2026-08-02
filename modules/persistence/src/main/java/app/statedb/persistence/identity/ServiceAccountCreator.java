package app.statedb.persistence.identity;

import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.auth.ApiTokenHasher;
import app.statedb.persistence.auth.RawApiTokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
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

  static final Set<Integer> ALLOWED_TTLS = Set.of(30, 90, 365);

  @Inject ApiTokenHasher hasher;

  /** The raw token to display once, plus the account it belongs to. */
  public record MintedServiceAccount(
      UUID accountId, String name, String rawToken, Instant expiresAt) {}

  /**
   * @param expiresInDays mandatory token lifetime, restricted to the console's supported choices
   * @param createdByUserId human console actor who minted the credential
   * @throws RequestValidationException (400) on a blank name or unsupported lifetime
   */
  @Transactional
  public MintedServiceAccount create(
      UUID orgId, String name, Integer expiresInDays, UUID createdByUserId) {
    if (name == null || name.isBlank() || name.strip().length() > 200) {
      throw new RequestValidationException("service account name must be 1-200 characters");
    }
    if (expiresInDays == null || !ALLOWED_TTLS.contains(expiresInDays)) {
      throw new RequestValidationException("expiresInDays must be one of 30, 90, or 365");
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
    Instant expiresAt = Instant.now().plus(expiresInDays, ChronoUnit.DAYS);

    ApiToken token = new ApiToken();
    token.tokenHash = hasher.hash(rawToken);
    token.description = name.strip();
    token.userId = account.id;
    token.orgId = orgId; // single_org credential scope: worth nothing outside this org
    token.kind = ApiToken.Kind.service_account;
    token.expiresAt = expiresAt;
    token.createdByUserId = createdByUserId;
    token.persistAndFlush();

    return new MintedServiceAccount(account.id, account.displayName, rawToken, expiresAt);
  }
}
