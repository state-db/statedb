package app.statedb.app.eula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.statedb.persistence.eula.EulaAcceptance;
import app.statedb.persistence.eula.EulaAcknowledgment;
import app.statedb.persistence.eula.EulaDocument;
import app.statedb.persistence.eula.EulaNotAcceptedException;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.User;
import app.statedb.persistence.stack.StackCreator;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The acceptance gate on stack creation.
 *
 * <p>The suite runs with {@code statedb.eula.accepted-version} set, so the deployment boots already
 * accepted. Each test here clears that row to put the deployment back in the state a fresh install
 * starts in, and restores an acceptance afterwards so the rest of the suite can create stacks. That
 * is only sound because {@link EulaAcknowledgment} reads the table on every call rather than
 * caching the answer — a test profile would force another Quarkus restart, which this module's
 * Cedar JNI library does not survive.
 */
@QuarkusTest
class EulaGateTest {

  private static final String ORG_NAME = "eula-gate-org";
  private static final String USER_SUB = "eula-gate-admin";

  @Inject StackCreator stackCreator;
  @Inject EulaAcknowledgment acknowledgment;
  @Inject EulaDocument document;
  @Inject EntityManager em;

  private UUID orgId;
  private UUID userId;

  @BeforeEach
  void seedUnacceptedDeployment() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM eula_acceptances").executeUpdate();

              Organization org = new Organization();
              org.name = ORG_NAME;
              org.displayName = "EULA Gate Test";
              org.persistAndFlush();
              orgId = org.id;

              User user = new User();
              user.iss = "local:test";
              user.sub = USER_SUB;
              user.email = "admin@example.com";
              user.displayName = "Gate Admin";
              user.persistAndFlush();
              userId = user.id;
            });
  }

  @AfterEach
  void restoreAcceptedDeployment() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG_NAME)
                  .executeUpdate();
              // Acceptance rows reference the accepting user and are never deleted in production,
              // so drop them before the user row goes.
              em.createNativeQuery("DELETE FROM eula_acceptances").executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE sub = ?1")
                  .setParameter(1, USER_SUB)
                  .executeUpdate();
            });
    // Every other test in this JVM creates stacks, so hand the deployment back accepted.
    acknowledgment.acceptFromConfiguration();
    assertTrue(acknowledgment.accepted(), "the suite's other tests need stack creation open");
  }

  @Test
  void aFreshDeploymentRefusesStackCreation() {
    assertFalse(acknowledgment.accepted(), "no acceptance is recorded");

    EulaNotAcceptedException denied =
        assertThrows(
            EulaNotAcceptedException.class,
            () -> stackCreator.create(orgId, ORG_NAME, "proj", "dev", Map.of()));

    assertEquals(document.version(), denied.requiredVersion());
    assertTrue(
        denied.getMessage().contains("End User License Agreement"),
        "the Pulumi CLI prints this message verbatim: " + denied.getMessage());
    assertEquals(0L, countStacks(), "the refusal must happen before any row is written");
  }

  @Test
  void acceptingInTheConsoleOpensStackCreation() {
    assertFalse(acknowledgment.status().accepted());

    EulaAcknowledgment.EulaStatus after = acknowledgment.accept(userId, "203.0.113.7");

    assertTrue(after.accepted());
    assertEquals(EulaAcceptance.Channel.console, after.acceptedVia());
    assertEquals(userId, after.acceptedByUserId());
    assertEquals(document.version(), after.version());
    assertEquals(document.sha256(), after.documentSha256());
    assertNotNull(after.acceptedAt());

    assertNotNull(stackCreator.create(orgId, ORG_NAME, "proj", "dev", Map.of()));
  }

  @Test
  void reacceptingIsIdempotent() {
    acknowledgment.accept(userId, "203.0.113.7");
    long first = countAcceptances();

    acknowledgment.accept(userId, "203.0.113.8");

    assertEquals(first, countAcceptances(), "a resubmitted form must not append a second record");
  }

  private long countStacks() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ((Number)
                        em.createNativeQuery(
                                "SELECT count(*) FROM stacks s JOIN organizations o ON o.id = s.org_id WHERE o.name = ?1")
                            .setParameter(1, ORG_NAME)
                            .getSingleResult())
                    .longValue());
  }

  private long countAcceptances() {
    return QuarkusTransaction.requiringNew().call(() -> EulaAcceptance.count());
  }
}
