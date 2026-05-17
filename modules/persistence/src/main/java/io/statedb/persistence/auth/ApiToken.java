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

  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Kind kind;

  @Column(name = "last_used_at")
  public Instant lastUsedAt;

  @Column(name = "expires_at")
  public Instant expiresAt;
}
