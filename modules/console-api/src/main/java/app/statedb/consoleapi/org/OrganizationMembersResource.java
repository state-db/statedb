package app.statedb.consoleapi.org;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.RequestValidationException;
import app.statedb.persistence.authz.*;
import app.statedb.persistence.identity.*;
import app.statedb.persistence.identity.OrgMembershipWriter.MembershipResult;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
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

  @GET
  @Authorize(action = "org:ManageMembers", loader = OrgEntityLoader.class)
  public List<MemberDto> list() {
    Organization org = authorized.subject(Organization.class);
    return OrgMember.<OrgMember>find(
            "select m from OrgMember m join fetch m.user where m.orgId = ?1 and m.role in ?2 order by m.user.email",
            org.id,
            OrgMember.Role.humanRoles())
        .list()
        .stream()
        .map(this::dto)
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

  private MemberDto dto(OrgMember member) {
    User user = member.user;
    List<GrantDto> grants = member.role == OrgMember.Role.guest ? grants(member) : List.of();
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

  private static List<GrantDto> grants(OrgMember member) {
    return grants(member.orgId, member.userId);
  }

  private static List<GrantDto> grants(UUID orgId, UUID userId) {
    return PolicyGrant.<PolicyGrant>list(
            "orgId = ?1 and principalUserId = ?2 and resourceStackId is not null order by createdAt",
            orgId,
            userId)
        .stream()
        .map(
            g -> {
              Stack stack = Stack.findById(g.resourceStackId);
              Project project = Project.findById(stack.projectId);
              return new GrantDto(stack.id, project.name, stack.name, g.templateId, g.createdAt);
            })
        .toList();
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
