package app.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.statedb.app.DatabaseTest;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.OrganizationInventoryFinder;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.Project;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.Workspace;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrganizationInventoryFinderTest extends DatabaseTest {

  private static final String EMPTY = "inventory-count-a-empty";
  private static final String POPULATED = "inventory-count-b-populated";

  @Inject OrganizationInventoryFinder finder;

  @Test
  @TestTransaction
  void includesEmptyOrganizationsAndCountsHumansAndStacksWithoutServices() {
    Organization empty = organization(EMPTY);
    Organization populated = organization(POPULATED);
    membership(populated, user("owner"), OrgMember.Role.owner);
    membership(populated, user("guest"), OrgMember.Role.guest);
    membership(populated, user("service"), OrgMember.Role.service);
    stack(populated, "apps", "prod");
    stack(populated, "apps", "staging");

    List<OrganizationInventoryFinder.OrganizationInventoryRow> testRows =
        finder.list().stream()
            .filter(row -> row.organization().name.startsWith("inventory-count-"))
            .toList();
    assertEquals(
        List.of(EMPTY, POPULATED), testRows.stream().map(row -> row.organization().name).toList());

    Map<String, OrganizationInventoryFinder.OrganizationInventoryRow> byName =
        testRows.stream()
            .collect(Collectors.toMap(row -> row.organization().name, Function.identity()));
    assertEquals(0, byName.get(EMPTY).humanMembers());
    assertEquals(0, byName.get(EMPTY).stacks());
    assertEquals(2, byName.get(POPULATED).humanMembers());
    assertEquals(2, byName.get(POPULATED).stacks());
  }

  private static Organization organization(String name) {
    Organization organization = new Organization();
    organization.name = name;
    organization.displayName = name;
    organization.persist();
    return organization;
  }

  private static User user(String sub) {
    User user = new User();
    user.iss = "local:organization-inventory-test";
    user.sub = sub;
    user.email = sub + "@example.test";
    user.persist();
    return user;
  }

  private static void membership(Organization org, User user, OrgMember.Role role) {
    OrgMember membership = new OrgMember();
    membership.orgId = org.id;
    membership.userId = user.id;
    membership.role = role;
    membership.persist();
  }

  private static void stack(Organization org, String projectName, String stackName) {
    Project project =
        Project.<Project>find("orgId = ?1 and name = ?2", org.id, projectName)
            .firstResultOptional()
            .orElseGet(
                () -> {
                  Project created = new Project();
                  created.orgId = org.id;
                  created.name = projectName;
                  created.persist();
                  return created;
                });
    Workspace workspace = new Workspace();
    workspace.orgId = org.id;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persist();
    Stack stack = new Stack();
    stack.orgId = org.id;
    stack.projectId = project.id;
    stack.workspaceId = workspace.id;
    stack.name = stackName;
    stack.persist();
  }
}
