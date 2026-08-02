package app.statedb.persistence.identity;

import io.quarkus.hibernate.orm.panache.common.ProjectedFieldName;
import java.util.UUID;

/** One organization a user belongs to, with the role they hold there. */
public record OrgMembershipView(
    @ProjectedFieldName("organization.id") UUID id,
    @ProjectedFieldName("organization.name") String name,
    @ProjectedFieldName("organization.displayName") String displayName,
    OrgMember.Role role) {}
