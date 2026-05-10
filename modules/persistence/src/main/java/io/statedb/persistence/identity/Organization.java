package io.statedb.persistence.identity;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization extends TimestampedEntity {

  @Column(nullable = false, unique = true)
  public String name;

  @Column(name = "display_name", nullable = false)
  public String displayName;
}
