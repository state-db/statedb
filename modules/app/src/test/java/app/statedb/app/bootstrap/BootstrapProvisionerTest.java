package app.statedb.app.bootstrap;

import static app.statedb.testsupport.TestTransactions.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.AdvisoryLock;
import app.statedb.persistence.bootstrap.BootstrapConfig;
import app.statedb.persistence.bootstrap.BootstrapProvisioner;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import io.quarkus.arc.Arc;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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

  @Inject AdvisoryLock advisoryLock;

  @Inject EntityManager em;

  private static final String CONCURRENT_ORG = "acme-concurrent";

  @Test
  @TestTransaction
  void coldBootCreatesOneRowInEachTable() {
    provisioner.provision(new BootstrapConfig("acme-cold", "Acme Cold"));

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
    assertNull(user.email, "bootstrap user carries no email");
    assertEquals("Break-glass admin", user.displayName);

    assertEquals(1, OrgMember.count("orgId = ?1 and userId = ?2", org.id, user.id));
    OrgMember m =
        OrgMember.<OrgMember>find("orgId = ?1 and userId = ?2", org.id, user.id).firstResult();
    assertEquals(OrgMember.Role.owner, m.role);
  }

  @Test
  @TestTransaction
  void identicalReBootIsNoOp() {
    BootstrapConfig config = new BootstrapConfig("acme-idemp", "Acme Idempotent");
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
  }

  @Test
  @TestTransaction
  void changedOrgDisplayNameIsUpdatedInPlace() {
    provisioner.provision(new BootstrapConfig("acme-rename", "Original Name"));
    provisioner.provision(new BootstrapConfig("acme-rename", "New Name"));

    assertEquals(1, Organization.count("name", "acme-rename"));
    Organization org = Organization.find("name", "acme-rename").firstResult();
    assertEquals("New Name", org.displayName);
  }

  /**
   * Multi-pod race: N threads each take {@link AdvisoryLock#BOOTSTRAP_KEY} and invoke {@link
   * BootstrapProvisioner#provision} against the same fresh org. The lock serializes them and the
   * per-row idempotent upserts make followers no-op, so the end state must be exactly one org /
   * membership. Cannot use {@code @TestTransaction} — multiple threads need real commits for the
   * advisory lock to hand off, so we clean the committed rows in {@link
   * #cleanupConcurrentBootstrapRows}.
   */
  @Test
  void concurrentBootstraps_produceSingleSetOfRows() throws Exception {
    int callers = 4;
    BootstrapConfig config = new BootstrapConfig(CONCURRENT_ORG, "Acme Concurrent");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Future<?>> futures = new ArrayList<>(callers);
    try {
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  Arc.container().requestContext().activate();
                  try {
                    advisoryLock.runExclusive(
                        AdvisoryLock.BOOTSTRAP_KEY, () -> provisioner.provision(config));
                  } finally {
                    Arc.container().requestContext().terminate();
                  }
                  return null;
                }));
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "callers did not finish within 30s");
      // Surface any worker exception — otherwise a partial-success run (e.g. 3 of 4 workers
      // commit, 1 throws) leaves committed rows that satisfy the assertions below and the
      // failure is invisible.
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertSingleBootstrapWasCommitted();
  }

  /**
   * The concurrent test commits real rows (advisory-lock semantics require commits, so
   * {@code @TestTransaction} can't isolate it). Strip them so sibling tests' counts stay clean.
   */
  @AfterEach
  @Transactional
  void cleanupConcurrentBootstrapRows() {
    em.createNativeQuery(
            "DELETE FROM org_members WHERE org_id IN"
                + " (SELECT id FROM organizations WHERE name = ?1)")
        .setParameter(1, CONCURRENT_ORG)
        .executeUpdate();
    em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
        .setParameter(1, CONCURRENT_ORG)
        .executeUpdate();
  }

  private void assertSingleBootstrapWasCommitted() {
    tx(
        () -> {
          assertEquals(1, Organization.count("name", CONCURRENT_ORG));
          Organization org = Organization.find("name", CONCURRENT_ORG).firstResult();
          User user =
              User.find(
                      "iss = ?1 and sub = ?2",
                      BootstrapProvisioner.BOOTSTRAP_ISS,
                      BootstrapProvisioner.BOOTSTRAP_SUB)
                  .firstResult();
          assertNotNull(user, "bootstrap user must exist");
          assertEquals(
              1,
              OrgMember.count("orgId = ?1 and userId = ?2", org.id, user.id),
              "exactly one membership for org+user");
        });
  }
}
