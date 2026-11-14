package app.statedb.persistence.lock;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Per-stack distributed lock. Does not extend {@link app.statedb.persistence.BaseEntity} because
 * the {@code stack_locks} table has no {@code created_at} column — {@code locked_at} is the
 * equivalent.
 *
 * <p>{@code lockTokenHash} is the SHA-256 of the canonical UUID string of the bearer token. The
 * plaintext is returned exactly once at acquire/renew and is never persisted. See {@link
 * LeaseTokenHasher}.
 */
@Entity
@Table(name = "stack_locks")
public class StackLock extends PanacheEntityBase {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  public UUID id;

  @Column(name = "stack_id", nullable = false, unique = true)
  public UUID stackId;

  @Column(name = "update_id")
  public UUID updateId;

  @Column(name = "locked_by", nullable = false)
  public String lockedBy;

  @Column(name = "lock_token_hash", nullable = false)
  public byte[] lockTokenHash;

  @Column(name = "locked_at", nullable = false)
  public Instant lockedAt;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @Column(name = "max_expires_at", nullable = false)
  public Instant maxExpiresAt;

  @Column(name = "heartbeat_at", nullable = false)
  public Instant heartbeatAt;
}
