package io.statedb.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * {@link BaseEntity} plus an {@code updated_at} maintained by Hibernate via {@link
 * UpdateTimestamp} (JVM clock) on every flush of a dirty entity. Writers that bypass Hibernate
 * (raw SQL, admin scripts) will not advance this column.
 */
@MappedSuperclass
public abstract class TimestampedEntity extends BaseEntity {

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
