package app.statedb.persistence.identity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    viewer,
    /**
     * Machine identity (CI service account). Outside the viewer ⊂ member ⊂ owner ladder — service
     * accounts have their own Cedar grant set (see {@code policies/service.cedar}) so tightening or
     * loosening CI permissions never reshapes human permissions.
     */
    service,
    /**
     * Additive-only human tier at the base of the ladder (guest ⊂ viewer ⊂ member ⊂ owner). A bare
     * guest matches no static policy: membership gives tenancy, instant revocation, and {@code
     * pulumi login}, while access is exactly the sum of the user's {@code policy_grants} rows. See
     * {@code design/stack-sharing.md}.
     */
    guest;

    private static final Set<Role> HUMAN_ROLES = Set.of(owner, member, viewer, guest);

    /** Roles in the human inheritance ladder; excludes the independent service-account role. */
    public static Set<Role> humanRoles() {
      return HUMAN_ROLES;
    }
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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "org_id", insertable = false, updatable = false)
  public Organization organization;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", insertable = false, updatable = false)
  public User user;

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
