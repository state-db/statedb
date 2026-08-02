package app.statedb.persistence.stack;

import app.statedb.persistence.TimestampedEntity;
import app.statedb.persistence.identity.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

  // Read-only navigation: writers set orgId directly so the DB FK / UNIQUE constraints
  // (and the composite FK targets in V002) remain the source of truth.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "org_id", insertable = false, updatable = false)
  public Organization organization;
}
