package io.statedb.persistence.identity;

import io.statedb.persistence.RequestValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

@ApplicationScoped
public class OrgMembershipWriter {
  private static final EnumSet<OrgMember.Role> HUMAN_ROLES =
      EnumSet.of(
          OrgMember.Role.owner, OrgMember.Role.member, OrgMember.Role.viewer, OrgMember.Role.guest);

  @Transactional
  public MembershipResult add(UUID orgId, UUID userId, OrgMember.Role role) {
    lockOrg(orgId);
    requireHuman(role);
    User user = User.findById(userId);
    if (user == null) throw new RequestValidationException("user does not exist");
    if (OrgMember.findById(new OrgMember.PK(orgId, userId)) != null) {
      throw new RequestValidationException("user is already a member");
    }
    OrgMember member = new OrgMember();
    member.orgId = orgId;
    member.userId = userId;
    member.role = role;
    member.persistAndFlush();
    return result(member, user);
  }

  @Transactional
  public MembershipResult changeRole(UUID orgId, UUID userId, OrgMember.Role role) {
    lockOrg(orgId);
    requireHuman(role);
    OrgMember member = humanMember(orgId, userId);
    if (member.role == OrgMember.Role.owner && role != OrgMember.Role.owner)
      requireAnotherOwner(orgId);
    member.role = role;
    member.persistAndFlush();
    return result(member, User.findById(userId));
  }

  @Transactional
  public void remove(UUID orgId, UUID userId) {
    lockOrg(orgId);
    OrgMember member = humanMember(orgId, userId);
    if (member.role == OrgMember.Role.owner) requireAnotherOwner(orgId);
    member.delete();
  }

  private static OrgMember humanMember(UUID orgId, UUID userId) {
    OrgMember member = OrgMember.findById(new OrgMember.PK(orgId, userId));
    if (member == null || member.role == OrgMember.Role.service) {
      throw new jakarta.ws.rs.NotFoundException();
    }
    return member;
  }

  private static void requireAnotherOwner(UUID orgId) {
    if (OrgMember.count("orgId = ?1 and role = ?2", orgId, OrgMember.Role.owner) <= 1) {
      throw new LastOwnerException();
    }
  }

  private static void requireHuman(OrgMember.Role role) {
    if (role == null || !HUMAN_ROLES.contains(role)) {
      throw new RequestValidationException("role must be owner, member, viewer, or guest");
    }
  }

  private static void lockOrg(UUID orgId) {
    if (Organization.findById(orgId, LockModeType.PESSIMISTIC_WRITE) == null) {
      throw new jakarta.ws.rs.NotFoundException();
    }
  }

  private static MembershipResult result(OrgMember member, User user) {
    return new MembershipResult(
        member.orgId, user.id, user.email, user.displayName, member.role, member.createdAt);
  }

  public record MembershipResult(
      UUID orgId,
      UUID userId,
      String email,
      String displayName,
      OrgMember.Role role,
      Instant joinedAt) {}
}
