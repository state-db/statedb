package io.statedb.persistence.stack;

import io.statedb.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stacks", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
public class Stack extends TimestampedEntity {

  // Denormalized; the composite FKs in V002 require it to match
  // projects.org_id and workspaces.org_id for the referenced rows.
  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Column(name = "project_id", nullable = false)
  public UUID projectId;

  @Column(nullable = false)
  public String name;

  @Column(name = "workspace_id", nullable = false, unique = true)
  public UUID workspaceId;

  @Column(nullable = false)
  public int version = 0;

  @Column(name = "active_update_id")
  public UUID activeUpdateId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public Map<String, String> tags;

  // Read-only navigation: writers set projectId directly so the DB UNIQUE constraint
  // on (project_id, name) and the composite FK targets in V002 remain the source of truth.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", insertable = false, updatable = false)
  public Project project;
}
