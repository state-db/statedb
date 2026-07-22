package io.statedb.consoleapi.user;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.security.identity.SecurityIdentity;
import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.AdminAllowList;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.OrgMembershipFinder;
import io.statedb.persistence.identity.ServiceAccountCreator;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Site-admin support/audit view of every identity in the {@code users} table — the humans who have
 * signed in via OIDC and the machine identities minted as service accounts. Read-only: admin status
 * itself is env-driven ({@code STATE_DB_ADMIN_SUBJECTS}), surfaced here per row as {@code
 * siteAdmin} but never mutated. Service-account rows are discriminated by {@code principalType} so
 * the console can segment them and suppress the human-only affordances (last-login, admin-subject
 * paste).
 */
@Path("/api/admin/users")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
public class AdminUsersResource {

  private static final int PAGE_SIZE = 50;

  @Inject SecurityIdentity identity;
  @Inject AdminAllowList adminAllowList;
  @Inject OrgMembershipFinder orgMembershipFinder;

  @GET
  public AdminUsersPage list(
      @QueryParam("page") Integer requestedPage,
      @QueryParam("includeService") boolean includeService) {
    requireAdmin();
    int page = requestedPage == null ? 1 : Math.max(1, requestedPage);
    PanacheQuery<User> query =
        includeService
            ? User.find("order by email nulls last")
            : User.find("iss <> ?1 order by email nulls last", ServiceAccountCreator.SERVICE_ISS);
    long total = query.count();
    List<User> users = query.page(Page.of(page - 1, PAGE_SIZE)).list();
    List<UUID> userIds = users.stream().map(user -> user.id).toList();
    Map<UUID, ApiToken> serviceTokens =
        userIds.isEmpty()
            ? Map.of()
            : ApiToken.<ApiToken>list(
                    "userId in ?1 and kind = ?2", userIds, ApiToken.Kind.service_account)
                .stream()
                .collect(
                    Collectors.toMap(
                        token -> token.userId,
                        token -> token,
                        (left, right) -> left.createdAt.isAfter(right.createdAt) ? left : right));
    Map<UUID, List<OrganizationDto>> orgsByUser =
        orgMembershipFinder.forUsers(userIds).stream()
            .collect(
                Collectors.groupingBy(
                    m -> m.userId(),
                    Collectors.mapping(
                        m -> new OrganizationDto(m.name(), m.displayName(), m.role()),
                        Collectors.toList())));
    List<AdminUserDto> rows =
        users.stream()
            .map(
                user ->
                    dto(
                        user,
                        orgsByUser.getOrDefault(user.id, List.of()),
                        serviceTokens.get(user.id)))
            .toList();
    return new AdminUsersPage(rows, page, PAGE_SIZE, total);
  }

  private AdminUserDto dto(User user, List<OrganizationDto> orgs, ApiToken serviceToken) {
    String principalType = ServiceAccountCreator.SERVICE_ISS.equals(user.iss) ? "service" : "human";
    return new AdminUserDto(
        user.id,
        user.iss,
        user.sub,
        user.email,
        user.displayName,
        principalType,
        adminAllowList.isAdmin(user.iss, user.sub),
        user.lastLoginAt,
        user.createdAt,
        user.deactivatedAt,
        "service".equals(principalType) ? credentialStatus(user, serviceToken) : null,
        orgs);
  }

  private static String credentialStatus(User user, ApiToken token) {
    if (token == null) return "no_credential";
    if (user.deactivatedAt != null || token.revokedAt != null) return "revoked";
    return token.expiresAt.isAfter(Instant.now()) ? "active" : "expired";
  }

  private void requireAdmin() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt)
        || !adminAllowList.isAdmin(jwt.getIssuer(), jwt.getSubject())) {
      throw new ForbiddenException();
    }
  }

  public record AdminUserDto(
      UUID id,
      String iss,
      String sub,
      String email,
      String displayName,
      String principalType,
      boolean siteAdmin,
      Instant lastLoginAt,
      Instant createdAt,
      Instant deactivatedAt,
      String credentialStatus,
      List<OrganizationDto> organizations) {}

  public record OrganizationDto(String name, String displayName, OrgMember.Role role) {}

  public record AdminUsersPage(List<AdminUserDto> users, int page, int pageSize, long total) {}
}
