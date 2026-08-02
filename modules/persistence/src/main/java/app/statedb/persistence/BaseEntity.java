package app.statedb.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Application-generated UUIDv7 primary key plus a {@code created_at} populated by Hibernate via
 * {@link CreationTimestamp} (JVM clock). The DB column also carries {@code DEFAULT now()} as a
 * safety net for any non-Hibernate inserts (admin SQL, seed scripts).
 */
@MappedSuperclass
public abstract class BaseEntity extends PanacheEntityBase {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  public UUID id;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;
}
