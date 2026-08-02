package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

/**
 * Smoke coverage for {@code pulumi config}. Config values live in the local {@code Pulumi.<stack>
 * .yaml}, so the only thing that reaches the backend is secret handling: {@code POST .../encrypt}
 * on {@code config set --secret} and {@code POST .../decrypt} on {@code config get}. {@code config
 * cp} does both, against two different stacks.
 *
 * <pre>
 *   config set / get / rm (plain + --secret)   roundTripsPlainAndSecretConfig
 *   config cp                                  roundTripsPlainAndSecretConfig
 * </pre>
 */
@QuarkusTest
class ConfigSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT = "config-smoke";
  private static final String STAGING_STACK = "staging";
  private static final String GREETING = "hello-from-config";
  private static final String API_KEY = "config-secret-value";

  @Test
  void roundTripsPlainAndSecretConfig() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(configProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);

    execResult("pulumi config set greeting " + GREETING);
    // The only config command that writes through to the backend: the value is encrypted with the
    // stack's service-managed key before it is written to Pulumi.dev.yaml.
    execResult("pulumi config set --secret apiKey " + API_KEY);

    assertContains(execResult("pulumi config get greeting").getStdout(), GREETING);
    assertContains(execResult("pulumi config get apiKey").getStdout(), API_KEY);

    // The listing masks the secret; only the explicit `config get` above decrypts it.
    JsonNode listed = execJson("pulumi config --json");
    assertEquals(GREETING, listed.required("config-smoke:greeting").required("value").asText());
    assertTrue(
        listed.required("config-smoke:apiKey").required("secret").asBoolean(),
        () -> "apiKey should be marked secret:\n" + listed);
    assertFalse(
        listed.toString().contains(API_KEY),
        () -> "config listing should not leak the secret value:\n" + listed);

    // The ciphertext on disk must round-trip through an actual update, not just `config get`.
    execResult("pulumi up --yes --non-interactive --skip-preview");
    JsonNode outputs = execJson("pulumi stack output --json --show-secrets");
    assertEquals(GREETING, outputs.required("greeting").asText());
    assertEquals(API_KEY, outputs.required("apiKey").asText());

    // cp decrypts against the source stack and re-encrypts against the destination.
    execResult("pulumi stack init " + STAGING_STACK);
    execResult("pulumi stack select " + STACK);
    execResult("pulumi config cp --dest " + STAGING_STACK);
    execResult("pulumi stack select " + STAGING_STACK);
    assertContains(execResult("pulumi config get greeting").getStdout(), GREETING);
    assertContains(execResult("pulumi config get apiKey").getStdout(), API_KEY);

    execResult("pulumi stack select " + STACK);
    execResult("pulumi config rm greeting");
    Container.ExecResult removed = execAllowingFailure("pulumi config get greeting");
    assertFalse(
        removed.getExitCode() == 0,
        () -> "config get should fail after rm:\n" + removed.getStdout() + removed.getStderr());
  }

  // Provider-free project that reads both a plain and a secret config value and echoes them back as
  // outputs, so `up` proves the ciphertext survives a real update.
  private static String configProject() {
    return """
        name: %s
        runtime: yaml
        description: Smoke project for the config commands.
        configuration:
          greeting:
            type: string
          apiKey:
            type: string
            secret: true
        outputs:
          greeting: ${greeting}
          apiKey: ${apiKey}
        """
        .formatted(PROJECT);
  }
}
