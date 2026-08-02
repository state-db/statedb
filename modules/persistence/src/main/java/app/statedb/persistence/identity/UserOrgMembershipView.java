package app.statedb.persistence.identity;

import io.quarkus.hibernate.orm.panache.common.ProjectedFieldName;
import java.util.UUID;

/**
 * A single membership carrying its owning {@code userId}, for grouping many users' orgs at once.
 */
public record UserOrgMembershipView(
    UUID userId,
    @ProjectedFieldName("organization.name") String name,
    @ProjectedFieldName("organization.displayName") String displayName,
    OrgMember.Role role) {}
