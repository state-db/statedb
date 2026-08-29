package app.statedb.consoleapi.org;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.authz.*;
import app.statedb.persistence.identity.*;
import app.statedb.persistence.identity.OrgMembershipWriter.MembershipResult;
import app.statedb.persistence.policy.MemberStackGrantFinder;
import app.statedb.persistence.policy.MemberStackGrantView;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.*;

@Path("/api/orgs/{org}/members")
@SessionPrincipal
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrganizationMembersResource {
  @Inject AuthorizedResource authorized;
  @Inject OrgMembershipWriter writer;
  @Inject MemberStackGrantFinder grantFinder;

  @GET
  @Authorize(action = "org:ManageMembers", loader = OrgEntityLoader.class)
  public List<MemberDto> list() {
    Organization org = authorized.subject(Organization.class);
    List<OrgMember> members =
        OrgMember.<OrgMember>find(
                "select m from OrgMember m join fetch m.user where m.orgId = ?1 and m.role in ?2 order by m.user.email",
                org.id,
                OrgMember.Role.humanRoles())
            .list();
    List<UUID> guestIds =
        members.stream()
            .filter(member -> member.role == OrgMember.Role.guest)
            .map(member -> member.userId)
            .toList();
    Map<UUID, List<GrantDto>> grantsByUser = new HashMap<>();
    grantFinder
        .forUsers(org.id, guestIds)
        .forEach(
            grant ->
                grantsByUser
                    .computeIfAbsent(grant.principalUserId(), ignored -> new ArrayList<>())
                    .add(grantDto(grant)));
    return members.stream()
        .map(member -> dto(member, grantsByUser.getOrDefault(member.userId, List.of())))
        .toList();
  }

  @GET
  @Path("/candidates")
  @Authorize(action = "org:ManageMembers", loader = OrgEntityLoader.class)
  public List<UserDto> candidates() {
    Organization org = authorized.subject(Organization.class);
    Set<UUID> memberIds =
        new HashSet<>(
            OrgMember.<OrgMember>list("orgId", org.id).stream().map(m -> m.userId).toList());
    return User.<User>list("order by email").stream()
        .filter(u -> !memberIds.contains(u.id))
        .map(u -> new UserDto(u.id, u.email, u.displayName))
        .toList();
  }

  @POST
  @Authorize(action = "org:ManageMembers", loader = OrgEntityLoader.class)
  public MemberDto add(MemberWrite body) {
    requireBody(body);
    Organization org = authorized.subject(Organization.class);
    return dto(writer.add(org.id, parseUser(body.userId), parseRole(body.role)));
  }

  @PATCH
  @Authorize(action = "org:ManageMembers", loader = OrgEntityLoader.class)
  public MemberDto update(MemberWrite body) {
    requireBody(body);
    Organization org = authorized.subject(Organization.class);
    return dto(writer.changeRole(org.id, parseUser(body.userId), parseRole(body.role)));
  }

  @DELETE
  @Authorize(action = "org:ManageMembers", loader = OrgEntityLoader.class)
  public Response remove(@QueryParam("userId") String userId) {
    Organization org = authorized.subject(Organization.class);
    writer.remove(org.id, parseUser(userId));
    return Response.ok().build();
  }

  private MemberDto dto(OrgMember member, List<GrantDto> grants) {
    User user = member.user;
    return new MemberDto(
        user.id, user.email, user.displayName, member.role, member.createdAt, grants);
  }

  private MemberDto dto(MembershipResult member) {
    List<GrantDto> grants =
        member.role() == OrgMember.Role.guest ? grants(member.orgId(), member.userId()) : List.of();
    return new MemberDto(
        member.userId(),
        member.email(),
        member.displayName(),
        member.role(),
        member.joinedAt(),
        grants);
  }

  private List<GrantDto> grants(UUID orgId, UUID userId) {
    return grantFinder.forUsers(orgId, List.of(userId)).stream()
        .map(OrganizationMembersResource::grantDto)
        .toList();
  }

  private static GrantDto grantDto(MemberStackGrantView grant) {
    return new GrantDto(
        grant.stackId(),
        grant.projectName(),
        grant.stackName(),
        grant.templateId(),
        grant.createdAt());
  }

  private static void requireBody(MemberWrite body) {
    if (body == null) throw new RequestValidationException("request body is required");
  }

  private static UUID parseUser(String raw) {
    if (raw == null) {
      throw new RequestValidationException("userId must be a UUID");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new RequestValidationException("userId must be a UUID");
    }
  }

  private static OrgMember.Role parseRole(String raw) {
    if (raw == null) {
      throw new RequestValidationException("invalid human role");
    }
    try {
      return OrgMember.Role.valueOf(raw);
    } catch (IllegalArgumentException e) {
      throw new RequestValidationException("invalid human role");
    }
  }

  public static class MemberWrite {
    @JsonProperty("userId")
    public String userId;

    @JsonProperty("role")
    public String role;
  }

  public record MemberDto(
      UUID userId,
      String email,
      String displayName,
      OrgMember.Role role,
      Instant joinedAt,
      List<GrantDto> grants) {}

  public record GrantDto(
      UUID stackId, String project, String stack, String template, Instant createdAt) {}

  public record UserDto(UUID id, String email, String displayName) {}
}
