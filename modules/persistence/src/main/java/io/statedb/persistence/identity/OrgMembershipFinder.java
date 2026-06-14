package io.statedb.persistence.identity;

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
}
