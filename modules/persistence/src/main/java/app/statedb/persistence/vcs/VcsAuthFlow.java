package app.statedb.persistence.vcs;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** One-time state for manifest registration, installation, and user authorization. */
@Entity
@Table(name = "vcs_auth_flows")
public class VcsAuthFlow extends PanacheEntityBase {

  public enum Purpose {
    manifest,
    install,
    oauth
  }

  @Id
  @Column(name = "state_hash", nullable = false)
  public byte[] stateHash;

  @Column(name = "initiated_by_user_id", nullable = false)
  public UUID initiatedByUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Purpose purpose;

  @Column(name = "org_id")
  public UUID orgId;

  @Column(name = "app_version")
  public Integer appVersion;

  @Column(name = "installation_id")
  public Long installationId;

  @Column(name = "code_verifier")
  public String codeVerifier;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;
}
