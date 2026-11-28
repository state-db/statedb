package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.OrgMembershipFinder;
import app.statedb.persistence.identity.OrgMembershipView;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The finder joins org_members to organizations and returns the user's role per org. */
@QuarkusTest
class OrgMembershipFinderTest extends DatabaseTest {

  @Inject OrgMembershipFinder finder;

  @Test
  @TestTransaction
  void returnsMembershipsForUser() {
    Organization org = new Organization();
    org.name = "memb-org";
    org.displayName = "Membership Org";
    org.persist();

    User user = new User();
    user.iss = "https://idp.test";
    user.sub = "member-1";
    user.email = "m@test.example";
    user.displayName = "Member One";
    user.persist();

    OrgMember member = new OrgMember();
    member.orgId = org.id;
    member.userId = user.id;
    member.role = OrgMember.Role.member;
    member.persist();

    List<OrgMembershipView> views = finder.forUser(user.id);

    assertEquals(1, views.size());
    assertEquals("memb-org", views.get(0).name());
    assertEquals("Membership Org", views.get(0).displayName());
    assertEquals(OrgMember.Role.member, views.get(0).role());
  }

  @Test
  @TestTransaction
  void returnsEmptyForUserWithNoOrgs() {
    User user = new User();
    user.iss = "https://idp.test";
    user.sub = "loner-1";
    user.persist();

    assertEquals(List.of(), finder.forUser(user.id));
  }
}
