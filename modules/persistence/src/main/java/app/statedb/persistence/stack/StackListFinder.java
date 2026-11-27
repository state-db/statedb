package app.statedb.persistence.stack;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.lock.StackLock;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.update.StackUpdate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Lists {@link StackSummary} rows readable by a principal. Backs Pulumi's {@code GET
 * /api/user/stacks} and the console workspace listing; duplicating the JOIN across modules would be
 * a tenant-isolation hazard.
 *
 * <p>The WHERE clause is the SQL mirror of the Cedar read policies — a stack is listed iff the PDP
 * would permit {@code stack:Read} (design/stack-sharing.md): owners and service accounts read their
 * orgs unconditionally; members/viewers read {@code internal} stacks; per-stack shares (guarded on
 * human membership, exactly as the template guard is) fill the rest, which is all a guest ever
 * gets. {@code StackEntityLoader} applies the same predicate on detail routes so unreadable stacks
 * 404. The list/PDP equivalence test pins this mirror to the PDP.
 *
 * <p>{@code join fetch} walks the {@code @ManyToOne} navigation on {@link Stack} to collapse
 * stack/project/organization/workspace into a single SQL statement.
 */
@ApplicationScoped
public class StackListFinder {

  private static final List<OrgMember.Role> FULL_READ_ROLES =
      List.of(OrgMember.Role.owner, OrgMember.Role.service);
  private static final List<OrgMember.Role> INTERNAL_READ_ROLES =
      List.of(OrgMember.Role.member, OrgMember.Role.viewer);

  /**
   * Native SQL mirror of {@link #readableByUser} for read-only console finders that alias {@code
   * stacks} as {@code s} and bind {@code :userId}. Kept here so tenant-isolation predicates live in
   * one place and stay pinned to Cedar by {@code StackSharingVisibilityTest}.
   */
  public static final String READABLE_BY_USER_SQL =
      """
      (
        EXISTS (
          SELECT 1 FROM org_members m
          WHERE m.user_id = :userId AND m.org_id = s.org_id AND m.role IN ('owner', 'service')
        )
        OR (
          s.visibility = 'internal'
          AND EXISTS (
            SELECT 1 FROM org_members m
            WHERE m.user_id = :userId AND m.org_id = s.org_id AND m.role IN ('member', 'viewer')
          )
        )
        OR (
          EXISTS (
            SELECT 1 FROM policy_grants g
            WHERE g.principal_user_id = :userId
              AND g.resource_stack_id = s.id
              AND g.template_id IN ('StackReadResourcePolicy', 'StackWriteResourcePolicy')
          )
          AND EXISTS (
            SELECT 1 FROM org_members m
            WHERE m.user_id = :userId AND m.org_id = s.org_id
              AND m.role IN ('owner', 'member', 'viewer', 'guest')
          )
        )
      )
      """;

  @Inject EntityManager em;

  public List<StackSummary> findVisibleToUser(UUID userId, String projectFilter) {
    return list(
        new StackListRequest(userId, null, null, projectFilter, null, null, null, Instant.now()));
  }

  public List<StackSummary> findVisibleToUser(
      UUID userId, String orgNameFilter, String projectFilter) {
    return list(
        new StackListRequest(
            userId, null, orgNameFilter, projectFilter, null, null, null, Instant.now()));
  }

  /** Finds one exactly named stack while applying the same read predicate as list endpoints. */
  public Optional<StackSummary> findVisibleToUser(
      UUID userId, String orgName, String project, String stackName) {
    return list(
            new StackListRequest(
                userId, null, orgName, project, stackName, null, null, Instant.now()))
        .stream()
        .findFirst();
  }

  // Single-org credentials (service-account tokens) additionally clamp to the token's org.
  public List<StackSummary> findVisibleToUserInOrg(UUID userId, UUID orgId, String projectFilter) {
    return list(
        new StackListRequest(userId, orgId, null, projectFilter, null, null, null, Instant.now()));
  }

  public PagedStacks findVisibleToUserPaged(
      UUID userId, String orgNameFilter, String search, String status, int page, int pageSize) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    StackListRequest request =
        new StackListRequest(
            userId, null, orgNameFilter, null, null, search, status, Instant.now());

    CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
    StackCriteria count = StackCriteria.from(countQuery);
    countQuery.select(cb.count(count.stack()));
    countQuery.where(predicates(cb, countQuery, count, request));
    long total = em.createQuery(countQuery).getSingleResult();

    int safePageSize = Math.max(1, pageSize);
    int effectivePage = clampPage(page, safePageSize, total);

    List<StackSummary> items = list(request, (effectivePage - 1) * safePageSize, safePageSize);
    return new PagedStacks(items, total, effectivePage);
  }

  private List<StackSummary> list(StackListRequest request) {
    return list(request, null, null);
  }

  private List<StackSummary> list(
      StackListRequest request, Integer firstResult, Integer maxResults) {
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Stack> listQuery = cb.createQuery(Stack.class);
    StackCriteria list = StackCriteria.from(listQuery);
    list.stack().fetch("project").fetch("organization");
    list.stack().fetch("workspace");
    listQuery.select(list.stack());
    listQuery.where(predicates(cb, listQuery, list, request));
    listQuery.orderBy(
        cb.asc(list.organization().get("name")),
        cb.asc(list.project().get("name")),
        cb.asc(list.stack().get("name")));

    var query = em.createQuery(listQuery);
    if (firstResult != null && maxResults != null) {
      query.setFirstResult(firstResult);
      query.setMaxResults(maxResults);
    }
    return query.getResultList().stream()
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

  private static Predicate[] predicates(
      CriteriaBuilder cb,
      CriteriaQuery<?> query,
      StackCriteria criteria,
      StackListRequest request) {
    List<Predicate> predicates = new java.util.ArrayList<>();
    if (request.orgId() != null) {
      predicates.add(cb.equal(criteria.stack().get("orgId"), request.orgId()));
    }
    if (request.orgName() != null) {
      predicates.add(cb.equal(criteria.organization().get("name"), request.orgName()));
    }
    if (request.project() != null) {
      predicates.add(cb.equal(criteria.project().get("name"), request.project()));
    }
    if (request.stackName() != null) {
      predicates.add(cb.equal(criteria.stack().get("name"), request.stackName()));
    }
    if (request.search() != null && !request.search().isBlank()) {
      String term = "%" + request.search().toLowerCase(Locale.ROOT) + "%";
      predicates.add(
          cb.or(
              cb.like(cb.lower(criteria.stack().get("name")), term),
              cb.like(cb.lower(criteria.project().get("name")), term),
              cb.like(cb.lower(criteria.organization().get("name")), term)));
    }
    if ("running".equals(request.status())) {
      predicates.add(
          cb.or(
              hasLiveLock(cb, query, criteria.stack(), request.now()),
              latestUpdateHasStatus(cb, query, criteria.stack(), StackUpdate.Status.IN_PROGRESS)));
    } else if ("failed".equals(request.status())) {
      predicates.add(
          latestAppliedUpdateHasStatus(cb, query, criteria.stack(), StackUpdate.Status.FAILED));
    }
    predicates.add(readableByUser(cb, query, criteria.stack(), request.userId()));
    return predicates.toArray(Predicate[]::new);
  }

  private static Predicate readableByUser(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack, UUID userId) {
    return cb.or(
        hasOrgRole(cb, query, stack, userId, FULL_READ_ROLES),
        cb.and(
            cb.equal(stack.get("visibility"), Stack.VISIBILITY_INTERNAL),
            hasOrgRole(cb, query, stack, userId, INTERNAL_READ_ROLES)),
        cb.and(
            hasStackReadGrant(cb, query, stack, userId),
            hasOrgRole(cb, query, stack, userId, OrgMember.Role.humanRoles())));
  }

  private static Predicate hasOrgRole(
      CriteriaBuilder cb,
      CriteriaQuery<?> query,
      Root<Stack> stack,
      UUID userId,
      Collection<OrgMember.Role> roles) {
    Subquery<Integer> subquery = query.subquery(Integer.class);
    Root<OrgMember> member = subquery.from(OrgMember.class);
    subquery.select(cb.literal(1));
    subquery.where(
        cb.equal(member.get("userId"), userId),
        cb.equal(member.get("orgId"), stack.get("orgId")),
        member.get("role").in(roles));
    return cb.exists(subquery);
  }

  private static Predicate hasStackReadGrant(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack, UUID userId) {
    Subquery<Integer> subquery = query.subquery(Integer.class);
    Root<PolicyGrant> grant = subquery.from(PolicyGrant.class);
    subquery.select(cb.literal(1));
    subquery.where(
        cb.equal(grant.get("principalUserId"), userId),
        cb.equal(grant.get("resourceStackId"), stack.get("id")),
        grant.get("templateId").in(PolicyGrant.STACK_READ_TEMPLATE_IDS));
    return cb.exists(subquery);
  }

  private static Predicate hasLiveLock(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack, Instant now) {
    Subquery<Integer> subquery = query.subquery(Integer.class);
    Root<StackLock> lock = subquery.from(StackLock.class);
    subquery.select(cb.literal(1));
    subquery.where(
        cb.equal(lock.get("stackId"), stack.get("id")), cb.greaterThan(lock.get("expiresAt"), now));
    return cb.exists(subquery);
  }

  private static Predicate latestAppliedUpdateHasStatus(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack, StackUpdate.Status status) {
    Subquery<Integer> subquery = query.subquery(Integer.class);
    Root<StackUpdate> update = subquery.from(StackUpdate.class);
    subquery.select(cb.literal(1));
    subquery.where(
        cb.equal(update.get("stackId"), stack.get("id")),
        cb.isFalse(update.get("dryRun")),
        cb.equal(update.get("status"), status),
        cb.equal(update.get("startedAt"), latestAppliedUpdateStartedAt(cb, query, stack)));
    return cb.exists(subquery);
  }

  private static Subquery<Instant> latestAppliedUpdateStartedAt(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack) {
    Subquery<Instant> subquery = query.subquery(Instant.class);
    Root<StackUpdate> update = subquery.from(StackUpdate.class);
    subquery.select(cb.greatest(update.<Instant>get("startedAt")));
    subquery.where(
        cb.equal(update.get("stackId"), stack.get("id")), cb.isFalse(update.get("dryRun")));
    return subquery;
  }

  private static Predicate latestUpdateHasStatus(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack, StackUpdate.Status status) {
    Subquery<Integer> subquery = query.subquery(Integer.class);
    Root<StackUpdate> update = subquery.from(StackUpdate.class);
    subquery.select(cb.literal(1));
    subquery.where(
        cb.equal(update.get("stackId"), stack.get("id")),
        cb.equal(update.get("status"), status),
        cb.equal(update.get("startedAt"), latestUpdateStartedAt(cb, query, stack)));
    return cb.exists(subquery);
  }

  private static Subquery<Instant> latestUpdateStartedAt(
      CriteriaBuilder cb, CriteriaQuery<?> query, Root<Stack> stack) {
    Subquery<Instant> subquery = query.subquery(Instant.class);
    Root<StackUpdate> update = subquery.from(StackUpdate.class);
    subquery.select(cb.greatest(update.<Instant>get("startedAt")));
    subquery.where(cb.equal(update.get("stackId"), stack.get("id")));
    return subquery;
  }

  private static int clampPage(int page, int pageSize, long total) {
    if (total == 0) {
      return 1;
    }
    long lastPage = ((total - 1) / pageSize) + 1;
    return (int) Math.min(Math.max(1, page), Math.min(lastPage, Integer.MAX_VALUE));
  }

  public record PagedStacks(List<StackSummary> items, long total, int page) {}

  private record StackListRequest(
      UUID userId,
      UUID orgId,
      String orgName,
      String project,
      String stackName,
      String search,
      String status,
      Instant now) {}

  private record StackCriteria(
      Root<Stack> stack, Join<Stack, Project> project, Join<Project, Organization> organization) {
    private static StackCriteria from(CriteriaQuery<?> query) {
      Root<Stack> stack = query.from(Stack.class);
      Join<Stack, Project> project = stack.join("project");
      Join<Project, Organization> organization = project.join("organization");
      return new StackCriteria(stack, project, organization);
    }
  }
}
