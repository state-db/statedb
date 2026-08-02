package app.statedb.persistence.stack;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.policy.PolicyGrant;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * Lists {@link StackSummary} rows readable by a principal. Backs Pulumi's {@code GET
 * /api/user/stacks} and the console workspace listing; duplicating the JOIN across modules would be
 * a tenant-isolation hazard.
 *
 * <p>The WHERE clause is the SQL mirror of the Cedar read policies — a stack is listed iff the PDP
 * would permit {@code stack:Read} (design/stack-sharing.md §3/§9): owners and service accounts read
 * their orgs unconditionally; members/viewers read {@code internal} stacks; per-stack shares
 * (guarded on human membership, exactly as the template guard is) fill the rest, which is all a
 * guest ever gets. {@code StackEntityLoader} applies the same predicate on detail routes so
 * unreadable stacks 404. The list/PDP equivalence test pins this mirror to the PDP.
 *
 * <p>{@code join fetch} walks the {@code @ManyToOne} navigation on {@link Stack} to collapse
 * stack/project/organization/workspace into a single SQL statement.
 */
@ApplicationScoped
public class StackListFinder {

  private static final String READABLE_BY_USER =
      "(exists (select 1 from OrgMember m where m.userId = :me and m.orgId = s.orgId"
          + "         and m.role in :fullReadRoles) "
          + " or (s.visibility = 'internal'"
          + "     and exists (select 1 from OrgMember m where m.userId = :me and m.orgId = s.orgId"
          + "                 and m.role in :internalReadRoles)) "
          + " or (exists (select 1 from PolicyGrant g where g.principalUserId = :me"
          + "             and g.resourceStackId = s.id and g.templateId in :readTemplates)"
          + "     and exists (select 1 from OrgMember m where m.userId = :me and m.orgId = s.orgId"
          + "                 and m.role in :humanRoles))) ";

  public List<StackSummary> findVisibleToUser(UUID userId, String projectFilter) {
    return find(userId, null, null, projectFilter);
  }

  public List<StackSummary> findVisibleToUser(
      UUID userId, String orgNameFilter, String projectFilter) {
    return find(userId, null, orgNameFilter, projectFilter);
  }

  // Single-org credentials (service-account tokens) additionally clamp to the token's org.
  public List<StackSummary> findVisibleToUserInOrg(UUID userId, UUID orgId, String projectFilter) {
    return find(userId, orgId, null, projectFilter);
  }

  private List<StackSummary> find(
      UUID userId, UUID orgId, String orgNameFilter, String projectFilter) {
    String hql =
        "from Stack s "
            + "join fetch s.project p "
            + "join fetch p.organization o "
            + "join fetch s.workspace w "
            + "where "
            + (orgId != null ? "s.orgId = :org and " : "")
            + (orgNameFilter != null ? "o.name = :orgName and " : "")
            + (projectFilter != null ? "p.name = :project and " : "")
            + READABLE_BY_USER
            + "order by o.name, p.name, s.name";

    Parameters params =
        Parameters.with("me", userId)
            .and("fullReadRoles", List.of(OrgMember.Role.owner, OrgMember.Role.service))
            .and("internalReadRoles", List.of(OrgMember.Role.member, OrgMember.Role.viewer))
            .and(
                "humanRoles",
                List.of(
                    OrgMember.Role.owner,
                    OrgMember.Role.member,
                    OrgMember.Role.viewer,
                    OrgMember.Role.guest))
            .and("readTemplates", PolicyGrant.STACK_READ_TEMPLATE_IDS);
    if (orgId != null) {
      params = params.and("org", orgId);
    }
    if (orgNameFilter != null) {
      params = params.and("orgName", orgNameFilter);
    }
    if (projectFilter != null) {
      params = params.and("project", projectFilter);
    }

    return Stack.<Stack>find(hql, params).list().stream()
        .map(
            s ->
                new StackSummary(
                    s.id,
                    s.orgId,
                    s.project.organization.name,
                    s.project.name,
                    s.project.createdAt,
                    s.name,
                    s.workspace.kind,
                    s.version,
                    null,
                    null))
        .toList();
  }
}
