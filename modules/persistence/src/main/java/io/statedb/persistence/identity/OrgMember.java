package io.statedb.persistence.identity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "org_members")
@IdClass(OrgMember.PK.class)
public class OrgMember extends PanacheEntityBase {

  public enum Role {
    owner,
    member,
    viewer
  }

  /** The role this user holds in the given org, if they are a member. */
  public static Optional<Role> findRole(UUID orgId, UUID userId) {
    return OrgMember.<OrgMember>find("orgId = ?1 and userId = ?2", orgId, userId)
        .firstResultOptional()
        .map(m -> m.role);
  }

  @Id
  @Column(name = "org_id", nullable = false)
  public UUID orgId;

  @Id
  @Column(name = "user_id", nullable = false)
  public UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, columnDefinition = "text")
  public Role role;

  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  public Instant createdAt;

  public static class PK implements Serializable {
    public UUID orgId;
    public UUID userId;

    public PK() {}

    public PK(UUID orgId, UUID userId) {
      this.orgId = orgId;
      this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof PK pk)) return false;
      return Objects.equals(orgId, pk.orgId) && Objects.equals(userId, pk.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(orgId, userId);
    }
  }
}
