package io.statedb.app.contract;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Replays the recorded fixtures under {@code tools/pulumi/recorder/fixtures/by-command/<command>}
 * against the live in-process Quarkus server. Add a new {@link Scenario} row to cover a new command
 * — no new test class required.
 */
@QuarkusTest
class ContractReplayTest {

  enum Scenario {
    LOGIN("01-login", FixtureReplay::seedIdentity),
    STACK_INIT("02-stack-init", FixtureReplay::seedIdentity),
    STACK_RM("10-stack-rm", FixtureReplay::seedIdentityWithStack);

    final String command;
    final Consumer<EntityManager> seed;

    Scenario(String command, Consumer<EntityManager> seed) {
      this.command = command;
      this.seed = seed;
    }
  }

  @Inject EntityManager em;

  @AfterEach
  void cleanup() {
    FixtureReplay.cleanup(em);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Scenario.class)
  void replaysFixtures(Scenario scenario) {
    scenario.seed.accept(em);
    FixtureReplay.command(scenario.command).replay();
  }
}
