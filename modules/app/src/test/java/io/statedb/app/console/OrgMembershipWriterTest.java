package io.statedb.app.console;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.*;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrgMembershipWriterTest {
  @Inject OrgMembershipWriter writer;
  private UUID orgId;
  private UUID ownerId;
  private UUID otherId;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = "membership-writer-org";
              org.displayName = "Membership writer";
              org.persistAndFlush();
              orgId = org.id;
              ownerId = user("membership-owner");
              otherId = user("membership-other");
              OrgMember owner = new OrgMember();
              owner.orgId = orgId;
              owner.userId = ownerId;
              owner.role = OrgMember.Role.owner;
              owner.persistAndFlush();
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization.delete("id", orgId);
              User.delete("sub in ?1", java.util.List.of("membership-owner", "membership-other"));
            });
  }

  @Test
  void finalOwnerCannotBeDemotedOrRemoved() {
    assertThrows(
        LastOwnerException.class, () -> writer.changeRole(orgId, ownerId, OrgMember.Role.member));
    assertThrows(LastOwnerException.class, () -> writer.remove(orgId, ownerId));
  }

  @Test
  void secondOwnerAllowsDemotion() {
    writer.add(orgId, otherId, OrgMember.Role.owner);
    writer.changeRole(orgId, ownerId, OrgMember.Role.guest);
    assertEquals(OrgMember.Role.guest, OrgMember.findRole(orgId, ownerId).orElseThrow());
  }

  @Test
  void serviceRoleIsRejected() {
    assertThrows(RuntimeException.class, () -> writer.add(orgId, otherId, OrgMember.Role.service));
  }

  private static UUID user(String sub) {
    User u = new User();
    u.iss = "local:test";
    u.sub = sub;
    u.email = sub + "@example.com";
    u.persistAndFlush();
    return u.id;
  }
}
