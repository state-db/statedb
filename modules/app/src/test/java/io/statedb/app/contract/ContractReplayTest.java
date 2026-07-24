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
 *
 * <p>Single-command scenarios pin isolated command shapes; the session scenarios chain whole
 * recorded CLI sessions in original order, so every exchange the CLI sent — stack CRUD, updates,
 * journals, events, exports, tags, rename — must reproduce against a clean server. Secrets
 * encrypt/decrypt exchanges are skipped inside {@link FixtureReplay} (recorded ciphertexts are not
 * decryptable here); they are covered by {@code SecretsFixtureReplayTest} and CLI smoke.
 */
@QuarkusTest
class ContractReplayTest {

  enum Scenario {
    LOGIN(FixtureReplay::seedIdentity, "01-login"),
    STACK_INIT(FixtureReplay::seedIdentity, "02-stack-init"),
    STACK_RM(FixtureReplay::seedIdentityWithStack, "10-stack-rm"),
    // The first recorded CLI session: init through up/preview/refresh/destroy to rm.
    LIFECYCLE_SESSION(
        FixtureReplay::seedIdentity,
        "02-stack-init",
        "03-up",
        "04-preview",
        "05-refresh",
        "06-stack-ls",
        "07-stack-export",
        "08-destroy",
        "09-stack-export-after-destroy",
        "10-stack-rm"),
    // The second recorded CLI session: output/history/tags/select/graph, the 501 surfaces
    // (schedule, webhook), destroy, secrets-provider change, rename, and teardown.
    METADATA_SESSION(
        FixtureReplay::seedIdentity,
        "11-stack-init",
        "12-up",
        "13-stack-output",
        "14-stack-get",
        "15-stack-history",
        "16-stack-tag-set",
        "17-stack-tag-ls",
        "18-stack-tag-get",
        "19-stack-tag-rm",
        "20-stack-select",
        "21-stack-unselect",
        "22-stack-graph",
        // 23-stack-drift recorded no HTTP exchanges (the command never reached the backend).
        // 24-stack-schedule / 25-stack-webhook recorded the stub faking support with 200s;
        // state-db deliberately 501s those surfaces (UnsupportedFeatureContractTest,
        // UnsupportedFeatureSmokeIT).
        "26-destroy",
        "27-change-secrets-provider",
        "28-stack-rename",
        "29-stack-ls",
        "30-stack-rm"),
    // `pulumi cancel` needs an update already in flight. The recorder seeds that out of band
    // (POST /meta/active-update) because no pulumi command leaves one behind; FixtureReplay seeds
    // the live equivalent when it sees a recorded forced cancel.
    CANCEL_SESSION(
        FixtureReplay::seedIdentity,
        "31-stack-init-for-cancel",
        "32-cancel",
        "33-stack-rm-after-cancel");

    final String[] commands;
    final Consumer<EntityManager> seed;

    Scenario(Consumer<EntityManager> seed, String... commands) {
      this.commands = commands;
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
    FixtureReplay.commands(scenario.commands).replay();
  }
}
