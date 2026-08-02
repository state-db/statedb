package app.statedb.persistence.identity;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * Lists the organizations a user belongs to, with their role in each.
 *
 * <p>The Panache projection follows {@link OrgMember#organization} and returns the complete view in
 * one query without hydrating an entity graph.
 */
@ApplicationScoped
public class OrgMembershipFinder {

  public List<OrgMembershipView> forUser(UUID userId) {
    return OrgMember.find("userId = ?1 ORDER BY organization.name", userId)
        .project(OrgMembershipView.class)
        .list();
  }

  /** Memberships for a page of users, each tagged with its {@code userId}. */
  public List<UserOrgMembershipView> forUsers(List<UUID> userIds) {
    if (userIds.isEmpty()) {
      return List.of();
    }
    return OrgMember.find("userId in ?1 ORDER BY organization.name", userIds)
        .project(UserOrgMembershipView.class)
        .list();
  }
}
