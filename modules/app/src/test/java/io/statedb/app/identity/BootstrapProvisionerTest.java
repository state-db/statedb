package io.statedb.app.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.identity.ApiToken;
import io.statedb.persistence.identity.BootstrapConfig;
import io.statedb.persistence.identity.BootstrapProvisioner;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * DB-touching tests for {@link BootstrapProvisioner}. Each test runs inside a
 * {@code @TestTransaction} that rolls back at the end, so tests do not interfere with each other or
 * with the Quarkus startup observer (which sees no bootstrap env in test profile and skips).
 *
 * <p>The bootstrap user lives at the fixed {@code (iss='local:bootstrap', sub='admin')}; multiple
 * provision calls inside one transaction target the same user row by design.
 */
@QuarkusTest
class BootstrapProvisionerTest {

  @Inject BootstrapProvisioner provisioner;

  @Test
  @TestTransaction
  void coldBootCreatesOneRowInEachTable() {
    BootstrapConfig config = config("acme-cold", "Acme Cold", "a");
    provisioner.provision(config);

    Organization org = Organization.find("name", "acme-cold").firstResult();
    assertNotNull(org, "organization row missing");
    assertEquals("Acme Cold", org.displayName);

    User user =
        User.find(
                "iss = ?1 and sub = ?2",
                BootstrapProvisioner.BOOTSTRAP_ISS,
                BootstrapProvisioner.BOOTSTRAP_SUB)
            .firstResult();
    assertNotNull(user, "bootstrap user row missing");
    assertEquals(config.adminEmail(), user.email);
    assertEquals(config.adminDisplayName(), user.displayName);

    assertEquals(1, OrgMember.count("orgId = ?1 and userId = ?2", org.id, user.id));
    OrgMember m =
        OrgMember.<OrgMember>find("orgId = ?1 and userId = ?2", org.id, user.id).firstResult();
    assertEquals(OrgMember.Role.owner, m.role);

    ApiToken token = ApiToken.find("tokenHash", config.adminTokenHash()).firstResult();
    assertNotNull(token, "bootstrap token row missing");
    assertEquals(ApiToken.Kind.service_account, token.kind);
    assertEquals("bootstrap", token.description);
    assertEquals(org.id, token.orgId);
    assertEquals(user.id, token.userId);
    assertNotNull(token.expiresAt);
    assertTrue(
        token.expiresAt.isAfter(Instant.now().plus(364, ChronoUnit.DAYS)),
        "expires_at must be ~1 year out");
    assertTrue(
        token.expiresAt.isBefore(Instant.now().plus(366, ChronoUnit.DAYS)),
        "expires_at must be ~1 year out");
  }

  @Test
  @TestTransaction
  void identicalReBootIsNoOp() {
    BootstrapConfig config = config("acme-idemp", "Acme Idempotent", "b");
    provisioner.provision(config);
    provisioner.provision(config);

    assertEquals(1, Organization.count("name", "acme-idemp"));
    assertEquals(
        1,
        User.count(
            "iss = ?1 and sub = ?2",
            BootstrapProvisioner.BOOTSTRAP_ISS,
            BootstrapProvisioner.BOOTSTRAP_SUB));
    User user =
        User.find(
                "iss = ?1 and sub = ?2",
                BootstrapProvisioner.BOOTSTRAP_ISS,
                BootstrapProvisioner.BOOTSTRAP_SUB)
            .firstResult();
    Organization org = Organization.find("name", "acme-idemp").firstResult();
    assertEquals(1, OrgMember.count("orgId = ?1 and userId = ?2", org.id, user.id));
    assertEquals(1, ApiToken.count("tokenHash", config.adminTokenHash()));
  }

  @Test
  @TestTransaction
  void changedOrgDisplayNameIsUpdatedInPlace() {
    provisioner.provision(config("acme-rename", "Original Name", "c"));
    provisioner.provision(config("acme-rename", "New Name", "c"));

    assertEquals(1, Organization.count("name", "acme-rename"));
    Organization org = Organization.find("name", "acme-rename").firstResult();
    assertEquals("New Name", org.displayName);
  }

  @Test
  @TestTransaction
  void changedAdminEmailAndDisplayNameAreUpdatedInPlace() {
    provisioner.provision(
        new BootstrapConfig(
            "acme-user-rename",
            "Acme",
            "old@acme.example",
            "Old Display",
            "sha256:" + "d".repeat(64)));
    provisioner.provision(
        new BootstrapConfig(
            "acme-user-rename",
            "Acme",
            "new@acme.example",
            "New Display",
            "sha256:" + "d".repeat(64)));

    User user =
        User.find(
                "iss = ?1 and sub = ?2",
                BootstrapProvisioner.BOOTSTRAP_ISS,
                BootstrapProvisioner.BOOTSTRAP_SUB)
            .firstResult();
    assertEquals("new@acme.example", user.email);
    assertEquals("New Display", user.displayName);
  }

  /**
   * Per the slice spec: re-running with a new token hash <em>adds</em> a row rather than rotating
   * the previous one — token revocation is operator-driven and not in scope for 4.5.
   */
  @Test
  @TestTransaction
  void newTokenHashAddsRowWithoutRevokingPrior() {
    BootstrapConfig first = config("acme-rotate", "Acme", "e");
    BootstrapConfig second =
        new BootstrapConfig(
            first.orgName(),
            first.orgDisplayName(),
            first.adminEmail(),
            first.adminDisplayName(),
            "sha256:" + "f".repeat(64));
    provisioner.provision(first);
    provisioner.provision(second);

    User user =
        User.find(
                "iss = ?1 and sub = ?2",
                BootstrapProvisioner.BOOTSTRAP_ISS,
                BootstrapProvisioner.BOOTSTRAP_SUB)
            .firstResult();
    assertEquals(2, ApiToken.count("userId", user.id));
    assertNotNull(ApiToken.find("tokenHash", first.adminTokenHash()).firstResult());
    assertNotNull(ApiToken.find("tokenHash", second.adminTokenHash()).firstResult());
  }

  private static BootstrapConfig config(String orgName, String orgDisplay, String hashSeed) {
    return new BootstrapConfig(
        orgName,
        orgDisplay,
        "ops@" + orgName + ".example",
        orgName + " Ops",
        "sha256:" + hashSeed.repeat(64));
  }
}
