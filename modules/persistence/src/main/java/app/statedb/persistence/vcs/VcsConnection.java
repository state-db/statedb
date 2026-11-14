package app.statedb.persistence.vcs;

import app.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/** An organization-scoped VCS host connection. */
@Entity
@Table(
    name = "vcs_connections",
    uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "host"}))
public class VcsConnection extends TimestampedEntity {

  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String host;

  @Column(name = "installation_id")
  public Long installationId;

  @Column(nullable = false)
  public boolean enabled = true;

  @Column(nullable = false)
  public int version = 1;
}
