package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.policy.PolicyGrant;
import app.statedb.persistence.stack.StackCreator;
import app.statedb.persistence.stack.StackRef;
import io.restassured.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class OrganizationMembershipFixture {
  static final String ORG = "members-resource-org";
  static final String OTHER_ORG = "members-resource-other-org";

  private OrganizationMembershipFixture() {}

  static void seedOrganizations() {
    cleanup();
    tx(
        () -> {
          persistOrganization(ORG);
          persistOrganization(OTHER_ORG);
        });
  }

  static void cleanup() {
    tx(
        () -> {
          Organization.delete("name in ?1", List.of(ORG, OTHER_ORG));
          User.delete("iss", "local:membership-resource");
        });
    OidcConsoleSessionFixture.cleanup();
  }

  static UUID organizationId() {
    return read(() -> Organization.<Organization>find("name", ORG).firstResult().id);
  }

  static void addServiceMember(UUID orgId, UUID serviceUserId) {
    tx(
        () -> {
          OrgMember service = new OrgMember();
          service.orgId = orgId;
          service.userId = serviceUserId;
          service.role = OrgMember.Role.service;
          service.persistAndFlush();
        });
  }

  static void grantAccessInBothOrganizations(StackCreator stackCreator, UUID guestId) {
    tx(
        () -> {
          Organization org = Organization.<Organization>find("name", ORG).firstResult();
          Organization other = Organization.<Organization>find("name", OTHER_ORG).firstResult();
          StackRef app = stackCreator.create(org.id, org.name, "apps", "prod", Map.of());
          StackRef otherStack = stackCreator.create(other.id, other.name, "apps", "prod", Map.of());
          grant(org.id, guestId, app.stackId(), "StackReadResourcePolicy");
          grant(org.id, guestId, app.stackId(), "StackWriteResourcePolicy");
          grant(other.id, guestId, otherStack.stackId(), "StackReadResourcePolicy");
        });
  }

  static Cookie ownerSession() {
    OidcConsoleSessionFixture.seedProvider();
    Cookie session = OidcConsoleSessionFixture.login();
    tx(
        () -> {
          User user = fixtureUser();
          Organization org = Organization.<Organization>find("name", ORG).firstResult();
          OrgMember membership = new OrgMember();
          membership.orgId = org.id;
          membership.userId = user.id;
          membership.role = OrgMember.Role.owner;
          membership.persistAndFlush();
        });
    return session;
  }

  static UUID fixtureUserId() {
    return read(() -> fixtureUser().id);
  }

  private static User fixtureUser() {
    User user =
        User.<User>find(
                "iss = ?1 and sub = ?2",
                OidcConsoleSessionFixture.FIXTURE_ISS,
                OidcConsoleSessionFixture.FIXTURE_SUB)
            .firstResult();
    assertNotNull(user, "OIDC callback provisions the fixture user");
    return user;
  }

  static UUID candidate(String sub, String email) {
    return read(
        () -> {
          User user = new User();
          user.iss = "local:membership-resource";
          user.sub = sub;
          user.email = email;
          user.persistAndFlush();
          return user.id;
        });
  }

  private static void persistOrganization(String name) {
    Organization org = new Organization();
    org.name = name;
    org.displayName = name;
    org.persistAndFlush();
  }

  private static void grant(UUID orgId, UUID userId, UUID stackId, String templateId) {
    PolicyGrant grant = new PolicyGrant();
    grant.orgId = orgId;
    grant.principalUserId = userId;
    grant.resourceStackId = stackId;
    grant.templateId = templateId;
    grant.persistAndFlush();
  }
}
