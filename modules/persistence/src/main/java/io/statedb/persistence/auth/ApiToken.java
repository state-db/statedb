package io.statedb.persistence.auth;

import io.statedb.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_tokens")
public class ApiToken extends BaseEntity {

  public enum Kind {
    oidc,
    service_account
  }

  @Column(name = "token_hash", nullable = false, unique = true)
  public String tokenHash;

  public String description;

  @Column(name = "user_id", nullable = false)
  public UUID userId;

  // Null means all_current_memberships credential scope, currently used by CLI OIDC tokens.
  // Non-null means single_org credential scope, currently used by service-account tokens.
  // Enforced by the api_tokens_org_scope CHECK constraint (V010).
  @Column(name = "org_id")
  public UUID orgId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Kind kind;

  @Column(name = "last_used_at")
  public Instant lastUsedAt;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @Column(name = "created_by_user_id")
  public UUID createdByUserId;

  @Column(name = "revoked_at")
  public Instant revokedAt;

  @Column(name = "revoked_by_user_id")
  public UUID revokedByUserId;
}
