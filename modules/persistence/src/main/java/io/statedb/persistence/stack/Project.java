package io.statedb.persistence.stack;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(name = "projects", uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "name"}))
public class Project extends TimestampedEntity {

  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Column(nullable = false)
  public String name;
}
