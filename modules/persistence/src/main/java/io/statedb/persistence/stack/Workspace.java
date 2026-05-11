package io.statedb.persistence.stack;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "workspaces")
public class Workspace extends TimestampedEntity {

  public enum Kind {
    pulumi,
    tofu
  }

  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Kind kind;
}
