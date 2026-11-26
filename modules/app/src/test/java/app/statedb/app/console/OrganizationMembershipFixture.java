package app.statedb.app.console;

import static app.statedb.testsupport.TestTransactions.read;
import static app.statedb.testsupport.TestTransactions.tx;

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
    tx(
        () -> {
          deleteRows();
          persistOrganization(ORG);
          persistOrganization(OTHER_ORG);
        });
  }

  static void cleanup() {
    tx(OrganizationMembershipFixture::deleteRows);
  }

  private static void deleteRows() {
    Organization.delete("name in ?1", List.of(ORG, OTHER_ORG));
    User.delete("iss", "local:membership-resource");
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
    return OidcConsoleSessionFixture.loginAs(ORG, OrgMember.Role.owner);
  }

  static UUID fixtureUserId() {
    return read(() -> OidcConsoleSessionFixture.fixtureUser().id);
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
