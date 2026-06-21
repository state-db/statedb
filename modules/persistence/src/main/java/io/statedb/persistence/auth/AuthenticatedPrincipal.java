package io.statedb.persistence.auth;

import io.statedb.persistence.identity.OrgMember;
import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated actor plus the credential used to authenticate them.
 *
 * <p>Org membership is never a property of the actor. Authorization combines this credential's
 * scope with the target resource's org in {@code PrincipalRoleResolver}.
 */
public record AuthenticatedPrincipal(UUID userId, String iss, String sub, Credential credential) {

  public AuthenticatedPrincipal(
      UUID userId,
      String iss,
      String sub,
      UUID tokenOrgId,
      String tokenOrgName,
      OrgMember.Role tokenOrgRole,
      ApiToken.Kind tokenKind,
      UUID apiTokenId) {
    this(
        userId,
        iss,
        sub,
        new Credential(
            switch (tokenKind) {
              case oidc -> CredentialKind.oidc_cli_token;
              case service_account -> CredentialKind.service_account_token;
            },
            apiTokenId,
            tokenOrgId == null
                ? CredentialScope.allCurrentMemberships()
                : CredentialScope.singleOrg(tokenOrgId, tokenOrgName, tokenOrgRole)));
  }

  public static AuthenticatedPrincipal consoleSession(UUID userId, String iss, String sub) {
    return new AuthenticatedPrincipal(
        userId,
        iss,
        sub,
        new Credential(
            CredentialKind.console_session, null, CredentialScope.allCurrentMemberships()));
  }

  public UUID apiTokenId() {
    return credential.id();
  }

  public CredentialScope credentialScope() {
    return credential.scope();
  }

  public enum CredentialKind {
    console_session,
    oidc_cli_token,
    service_account_token
  }

  /** How the request authenticated, and which orgs that credential may be used against. */
  public record Credential(CredentialKind kind, UUID id, CredentialScope scope) {}

  public enum CredentialScopeKind {
    all_current_memberships,
    single_org
  }

  /**
   * A credential's org boundary. {@code all_current_memberships} means "resolve against live
   * org_members for the resource org"; {@code single_org} means "only this org, with this cached
   * role."
   */
  public record CredentialScope(
      CredentialScopeKind kind, UUID orgId, String orgName, OrgMember.Role role) {

    public static CredentialScope allCurrentMemberships() {
      return new CredentialScope(CredentialScopeKind.all_current_memberships, null, null, null);
    }

    public static CredentialScope singleOrg(UUID orgId, String orgName, OrgMember.Role role) {
      return new CredentialScope(CredentialScopeKind.single_org, orgId, orgName, role);
    }

    public boolean includes(UUID resourceOrgId) {
      return switch (kind) {
        case all_current_memberships -> true;
        case single_org -> orgId != null && orgId.equals(resourceOrgId);
      };
    }

    public Optional<UUID> singleOrgId() {
      return kind == CredentialScopeKind.single_org ? Optional.of(orgId) : Optional.empty();
    }

    public Optional<String> singleOrgName() {
      return kind == CredentialScopeKind.single_org
          ? Optional.ofNullable(orgName)
          : Optional.empty();
    }

    public boolean resolvesRoleFromMemberships() {
      return kind == CredentialScopeKind.all_current_memberships;
    }
  }
}
