package io.statedb.persistence.auth;

import io.statedb.persistence.identity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;

/**
 * Mints a 24-hour, account-scoped {@code kind=oidc} CLI token for a user after a console SSO
 * session has established who they are.
 *
 * <p>The token is <b>not</b> scoped to one org: it authenticates the user, and authorization is
 * resolved per request against the resource's org by {@link
 * io.statedb.persistence.authz.PrincipalRoleResolver} — exactly like the console session it was
 * minted from. This matches Pulumi's own token model (the org is chosen by the stack path, not the
 * token) and is why minting needs no org argument and no membership check here: the token can never
 * grant more than the user's live memberships allow, re-checked on every request.
 *
 * <p>The raw token is generated here, returned once to the caller, and never stored — only its
 * {@link ApiTokenHasher} digest lands in {@code api_tokens.token_hash}, matching the lookup the
 * Pulumi {@code TokenAuthFilter} performs on every authenticated request.
 */
@ApplicationScoped
public class CliTokenMinter {

  static final Duration TTL = Duration.ofHours(24);

  @Inject ApiTokenHasher hasher;

  /** The raw token to display once, plus the expiry to surface to the user. */
  public record MintedCliToken(String rawToken, Instant expiresAt) {}

  @Transactional
  public MintedCliToken mint(String iss, String sub, String description) {
    // The user was provisioned at console login, so this is an invariant, not a runtime branch.
    User user =
        User.<User>find("iss = ?1 and sub = ?2", iss, sub)
            .firstResultOptional()
            .orElseThrow(() -> new IllegalStateException("no user for " + iss + ":" + sub));

    String rawToken = RawApiTokens.generate();
    Instant expiresAt = Instant.now().plus(TTL);

    ApiToken token = new ApiToken();
    token.tokenHash = hasher.hash(rawToken);
    token.description = (description == null || description.isBlank()) ? "CLI token" : description;
    token.userId = user.id;
    token.orgId = null; // account-scoped: role resolved per request, not pinned to one org
    token.kind = ApiToken.Kind.oidc;
    token.expiresAt = expiresAt;
    token.persist();

    return new MintedCliToken(rawToken, expiresAt);
  }
}
