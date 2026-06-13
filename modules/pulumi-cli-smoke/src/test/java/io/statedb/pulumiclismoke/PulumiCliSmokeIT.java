package io.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.auth.ApiToken;
import io.statedb.persistence.identity.OrgMember;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.identity.User;
import io.statedb.testsupport.PulumiProject;
import io.statedb.testsupport.RepoRoot;
import io.statedb.testsupport.TestIdentity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@QuarkusTest
class PulumiCliSmokeIT {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DockerImageName PULUMI_IMAGE =
      DockerImageName.parse("pulumi/pulumi:3.234.0");

  private static final String ORG = "acme";
  private static final String STACK = "dev";
  private static final String STAGING_STACK = "staging";
  private static final String INSPECT_PROJECT = "stack-subcommands";
  private static final String RAW_TOKEN = "pulumi-cli-smoke-token";
  private static final String PRODUCER_PROJECT = "state-db-workbench";
  private static final String CONSUMER_PROJECT = "stackref-consumer";
  private static final String STACK_REF = ORG + "/" + PRODUCER_PROJECT + "/" + STACK;
  private static final String PLAIN_VALUE = "plain-from-producer";
  private static final String SECRET_VALUE = "secret-from-producer";
  private static final String SECRET_SIGNATURE_KEY = "4dabf18193072939515e22adb298388d";
  private static final String SECRET_SIGNATURE_VALUE = "1b47061264138c4ac30d75fd1eb44270";

  @TestHTTPResource("/")
  URL baseUrl;

  @Inject EntityManager em;

  private GenericContainer<?> pulumi;

  @BeforeEach
  void seed() {
    seedAcmeIdentity();
  }

  @AfterEach
  void stopContainerAndCleanup() {
    if (pulumi != null) {
      pulumi.stop();
    }
    cleanupAcmeIdentity();
  }

  @Test
  void realPulumiCliCanCreateListAndRemoveAnEmptyStack() throws Exception {
    String backendUrl = "http://host.testcontainers.internal:" + baseUrl.getPort() + "/pulumi";
    Testcontainers.exposeHostPorts(baseUrl.getPort());

    MountableFile recorderProject =
        MountableFile.forHostPath(RepoRoot.get().resolve(PulumiProject.RECORDER_PROJECT_PATH));
    pulumi =
        new GenericContainer<>(PULUMI_IMAGE)
            .withEnv("PULUMI_ACCESS_TOKEN", RAW_TOKEN)
            .withEnv("PULUMI_CONFIG_PASSPHRASE", "test")
            .withEnv("PULUMI_HOME", "/tmp/pulumi-home")
            .withEnv("PULUMI_SKIP_CONFIRMATIONS", "true")
            .withEnv("PULUMI_SKIP_UPDATE_CHECK", "true")
            .withCopyFileToContainer(recorderProject, "/workspace/Pulumi.yaml")
            .withCopyToContainer(Transferable.of("{}\n"), "/workspace/Pulumi.dev.yaml")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand("-lc", "mkdir -p /tmp/pulumi-home && sleep infinity")
            .withStartupTimeout(Duration.ofMinutes(2));
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);

    JsonNode stacksAfterInit = execJson("pulumi stack ls --json");
    assertTrue(
        stackNames(stacksAfterInit).contains(STACK),
        () -> "stack ls should include " + STACK + ":\n" + stacksAfterInit);

    execResult("pulumi stack rm " + STACK + " --yes");

    JsonNode stacksAfterRemove = execJson("pulumi stack ls --json");
    assertFalse(
        stackNames(stacksAfterRemove).contains(STACK),
        () -> "stack ls should not include " + STACK + ":\n" + stacksAfterRemove);
  }

  @Test
  void realPulumiCliCanImportAndExportAStackCheckpoint() throws Exception {
    String backendUrl = "http://host.testcontainers.internal:" + baseUrl.getPort() + "/pulumi";
    Testcontainers.exposeHostPorts(baseUrl.getPort());

    MountableFile recorderProject =
        MountableFile.forHostPath(RepoRoot.get().resolve(PulumiProject.RECORDER_PROJECT_PATH));
    pulumi =
        new GenericContainer<>(PULUMI_IMAGE)
            .withEnv("PULUMI_ACCESS_TOKEN", RAW_TOKEN)
            .withEnv("PULUMI_CONFIG_PASSPHRASE", "test")
            .withEnv("PULUMI_HOME", "/tmp/pulumi-home")
            .withEnv("PULUMI_SKIP_CONFIRMATIONS", "true")
            .withEnv("PULUMI_SKIP_UPDATE_CHECK", "true")
            .withEnv("PULUMI_COLOR", "never")
            .withCopyFileToContainer(recorderProject, "/workspace/Pulumi.yaml")
            .withCopyToContainer(Transferable.of("{}\n"), "/workspace/Pulumi.dev.yaml")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand("-lc", "mkdir -p /tmp/pulumi-home && sleep infinity")
            .withStartupTimeout(Duration.ofMinutes(2));
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi stack export --file /workspace/import.json");
    JsonNode before = execJson("cat /workspace/import.json");

    execResult("pulumi stack import --file /workspace/import.json");

    JsonNode after = execJson("pulumi stack export");
    assertEquals(normalizeExport(before), normalizeExport(after));
  }

  @Test
  void realPulumiCliPreservesStackReferenceOutputsAndSecrets() throws Exception {
    String backendUrl = "http://host.testcontainers.internal:" + baseUrl.getPort() + "/pulumi";
    Testcontainers.exposeHostPorts(baseUrl.getPort());

    pulumi =
        pulumiContainer(
            Map.of(
                "/producer-Pulumi.yaml",
                producerProject(),
                "/producer-Pulumi.dev.yaml",
                "{}\n",
                "/consumer-Pulumi.yaml",
                consumerProject(),
                "/consumer-Pulumi.dev.yaml",
                "{}\n"));
    pulumi.start();

    execResult("/workspace/producer", "pulumi login " + backendUrl);
    execResult("/workspace/producer", "pulumi stack init " + STACK);
    execResult("/workspace/producer", "pulumi up --yes --non-interactive");
    JsonNode producerMaskedOutputs = execJson("/workspace/producer", "pulumi stack output --json");
    assertEquals(PLAIN_VALUE, producerMaskedOutputs.required("plain").asText());
    assertEquals("[secret]", producerMaskedOutputs.required("secret").asText());
    JsonNode producerVisibleOutputs =
        execJson("/workspace/producer", "pulumi stack output --json --show-secrets");
    assertEquals(SECRET_VALUE, producerVisibleOutputs.required("secret").asText());

    execResult("/workspace/consumer", "pulumi login " + backendUrl);
    execResult("/workspace/consumer", "pulumi stack init " + STACK);

    Container.ExecResult preview =
        execResult("/workspace/consumer", "pulumi preview --non-interactive --diff");
    assertContains(preview.getStdout(), "plainFromRef");
    assertContains(preview.getStdout(), PLAIN_VALUE);
    assertContains(preview.getStdout(), "secretFromRef");
    assertContains(preview.getStdout(), "[secret]");
    assertFalse(
        preview.getStdout().contains(SECRET_VALUE),
        () -> "preview should not leak secret value:\n" + preview.getStdout());

    Container.ExecResult up =
        execResult("/workspace/consumer", "pulumi up --yes --non-interactive");
    assertContains(up.getStdout(), "plainFromRef");
    assertContains(up.getStdout(), PLAIN_VALUE);
    assertContains(up.getStdout(), "secretFromRef");
    assertContains(up.getStdout(), "[secret]");
    assertFalse(
        up.getStdout().contains(SECRET_VALUE),
        () -> "up should not leak secret value:\n" + up.getStdout());

    JsonNode maskedOutputs = execJson("/workspace/consumer", "pulumi stack output --json");
    assertEquals(PLAIN_VALUE, maskedOutputs.required("plainFromRef").asText());
    assertEquals("[secret]", maskedOutputs.required("secretFromRef").asText());

    JsonNode visibleOutputs =
        execJson("/workspace/consumer", "pulumi stack output --json --show-secrets");
    assertEquals(PLAIN_VALUE, visibleOutputs.required("plainFromRef").asText());
    assertEquals(SECRET_VALUE, visibleOutputs.required("secretFromRef").asText());

    JsonNode exported = execJson("/workspace/consumer", "pulumi stack export");
    JsonNode exportedOutputs = rootStackOutputs(exported);
    assertEquals(PLAIN_VALUE, exportedOutputs.required("plainFromRef").asText());
    JsonNode exportedSecret = exportedOutputs.required("secretFromRef");
    assertEquals(SECRET_SIGNATURE_VALUE, exportedSecret.required(SECRET_SIGNATURE_KEY).asText());
    assertTrue(
        exportedSecret.has("ciphertext"),
        () -> "secret output should remain encrypted: " + exportedSecret);
    assertFalse(
        exported.toString().contains(SECRET_VALUE),
        () -> "default stack export should not leak secret value:\n" + exported);
  }

  @Test
  void realPulumiCliSupportsStackInspectionTagAndHistoryCommands() throws Exception {
    String backendUrl = "http://host.testcontainers.internal:" + baseUrl.getPort() + "/pulumi";
    Testcontainers.exposeHostPorts(baseUrl.getPort());

    pulumi = singleProjectContainer(inspectionProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive");

    // tag round-trip: set a tag, see it via ls/get, then remove it and confirm it is gone.
    execResult("pulumi stack tag set env smoke");
    JsonNode tagsWithEnv = execJson("pulumi stack tag ls --json");
    assertEquals(
        "smoke",
        tagsWithEnv.path("env").asText(),
        () -> "stack tag ls should report the tag we just set:\n" + tagsWithEnv);
    assertContains(execResult("pulumi stack tag get env").getStdout(), "smoke");
    execResult("pulumi stack tag rm env");
    JsonNode tagsWithoutEnv = execJson("pulumi stack tag ls --json");
    assertFalse(
        tagsWithoutEnv.has("env"), () -> "stack tag rm should drop the tag:\n" + tagsWithoutEnv);

    // bare `stack` resolves and prints the current stack via GET stack/export. (`stack get` is not
    // a real subcommand in the pinned CLI, so it is not covered here.)
    assertContains(execResult("pulumi stack").getStdout(), STACK);

    // graph builds a DOT file from the exported checkpoint.
    execResult("pulumi stack graph /workspace/graph.dot");
    assertContains(execResult("cat /workspace/graph.dot").getStdout(), "digraph");

    // history lists the update we just ran (previews and renames are excluded server-side).
    JsonNode history = execJson("pulumi stack history --json");
    assertTrue(history.isArray() && history.size() >= 1, () -> "expected >=1 update:\n" + history);
    assertEquals(
        "update", history.get(0).path("kind").asText(), () -> "newest update kind:\n" + history);

    // select / unselect are CLI-local state changes that still resolve the stack server-side.
    execResult("pulumi stack select " + STACK);
    execResult("pulumi stack unselect");
    execResult("pulumi stack select " + STACK);
  }

  @Test
  void realPulumiCliRenamesStacksAndRejectsUnsupportedFeatures() throws Exception {
    String backendUrl = "http://host.testcontainers.internal:" + baseUrl.getPort() + "/pulumi";
    Testcontainers.exposeHostPorts(baseUrl.getPort());

    pulumi = singleProjectContainer(inspectionProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive");

    // schedule/webhook are Pulumi Cloud features this backend deliberately 501s; the CLI must fail
    // cleanly (non-zero exit), not hang or treat the error as success.
    Container.ExecResult schedule = execAllowingFailure("pulumi stack schedule list");
    assertFalse(
        schedule.getExitCode() == 0,
        () ->
            "stack schedule list should fail against an unsupported backend:\n"
                + schedule.getStdout()
                + schedule.getStderr());
    Container.ExecResult webhook = execAllowingFailure("pulumi stack webhook list");
    assertFalse(
        webhook.getExitCode() == 0,
        () ->
            "stack webhook list should fail against an unsupported backend:\n"
                + webhook.getStdout()
                + webhook.getStderr());

    // rename moves the stack server-side (metadata + checkpoint rewrite); ls must reflect the move.
    execResult("pulumi stack rename " + STAGING_STACK);
    JsonNode stacks = execJson("pulumi stack ls --json");
    assertTrue(
        stackNames(stacks).contains(STAGING_STACK),
        () -> "stack ls should show the renamed stack:\n" + stacks);
    assertFalse(
        stackNames(stacks).contains(STACK),
        () -> "stack ls should no longer show the old name:\n" + stacks);
  }

  private JsonNode execJson(String command) throws Exception {
    return JSON.readTree(execResult(command).getStdout());
  }

  private JsonNode execJson(String workdir, String command) throws Exception {
    return JSON.readTree(execResult(workdir, command).getStdout());
  }

  private Container.ExecResult execResult(String command) throws Exception {
    return execResult("/workspace", command);
  }

  private Container.ExecResult execResult(String workdir, String command) throws Exception {
    Container.ExecResult result =
        pulumi.execInContainer("sh", "-lc", "cd " + workdir + " && " + command);
    assertEquals(
        0,
        result.getExitCode(),
        () -> command + "\nstdout:\n" + result.getStdout() + "\nstderr:\n" + result.getStderr());
    return result;
  }

  // Like execResult but tolerates a non-zero exit, for commands we expect the CLI to reject (the
  // unsupported schedule/webhook 501s). The caller asserts on the exit code.
  private Container.ExecResult execAllowingFailure(String command) throws Exception {
    return pulumi.execInContainer("sh", "-lc", "cd /workspace && " + command);
  }

  private GenericContainer<?> pulumiContainer(Map<String, String> files) {
    GenericContainer<?> container =
        new GenericContainer<>(PULUMI_IMAGE)
            .withEnv("PULUMI_ACCESS_TOKEN", RAW_TOKEN)
            .withEnv("PULUMI_CONFIG_PASSPHRASE", "test")
            .withEnv("PULUMI_HOME", "/tmp/pulumi-home")
            .withEnv("PULUMI_SKIP_CONFIRMATIONS", "true")
            .withEnv("PULUMI_SKIP_UPDATE_CHECK", "true")
            .withEnv("PULUMI_COLOR", "never")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand(
                "-lc",
                "mkdir -p /tmp/pulumi-home /workspace/producer /workspace/consumer"
                    + " && mv /producer-Pulumi.yaml /workspace/producer/Pulumi.yaml"
                    + " && mv /producer-Pulumi.dev.yaml /workspace/producer/Pulumi.dev.yaml"
                    + " && mv /consumer-Pulumi.yaml /workspace/consumer/Pulumi.yaml"
                    + " && mv /consumer-Pulumi.dev.yaml /workspace/consumer/Pulumi.dev.yaml"
                    + " && sleep infinity")
            .withStartupTimeout(Duration.ofMinutes(2));

    files.forEach((path, content) -> container.withCopyToContainer(Transferable.of(content), path));
    return container;
  }

  // Single-project container at /workspace, mirroring the first two tests' setup but with an inline
  // project body instead of the recorder Pulumi.yaml.
  private GenericContainer<?> singleProjectContainer(String pulumiYaml) {
    return new GenericContainer<>(PULUMI_IMAGE)
        .withEnv("PULUMI_ACCESS_TOKEN", RAW_TOKEN)
        .withEnv("PULUMI_CONFIG_PASSPHRASE", "test")
        .withEnv("PULUMI_HOME", "/tmp/pulumi-home")
        .withEnv("PULUMI_SKIP_CONFIRMATIONS", "true")
        .withEnv("PULUMI_SKIP_UPDATE_CHECK", "true")
        .withEnv("PULUMI_COLOR", "never")
        .withCopyToContainer(Transferable.of(pulumiYaml), "/workspace/Pulumi.yaml")
        .withCopyToContainer(Transferable.of("{}\n"), "/workspace/Pulumi.dev.yaml")
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
        .withCommand("-lc", "mkdir -p /tmp/pulumi-home && sleep infinity")
        .withStartupTimeout(Duration.ofMinutes(2));
  }

  // A provider-free project (so `up` needs no plugin download) that still produces a real update,
  // a checkpoint to graph/export, and a secret output to exercise batch-decrypt.
  private static String inspectionProject() {
    return """
        name: %s
        runtime: yaml
        description: Stack subcommand smoke project.
        outputs:
          plain: %s
          secret:
            fn::secret: %s
        """
        .formatted(INSPECT_PROJECT, PLAIN_VALUE, SECRET_VALUE);
  }

  private static String producerProject() {
    return """
        name: %s
        runtime: yaml
        description: Producer stack for StackReference CLI smoke tests.
        outputs:
          plain: %s
          secret:
            fn::secret: %s
        """
        .formatted(PRODUCER_PROJECT, PLAIN_VALUE, SECRET_VALUE);
  }

  private static String consumerProject() {
    return """
        name: %s
        runtime: yaml
        description: Consumer stack for StackReference CLI smoke tests.
        resources:
          source:
            type: pulumi:pulumi:StackReference
            properties:
              name: %s
        outputs:
          plainFromRef: ${source.outputs["plain"]}
          secretFromRef: ${source.outputs["secret"]}
        """
        .formatted(CONSUMER_PROJECT, STACK_REF);
  }

  private static JsonNode rootStackOutputs(JsonNode exported) {
    for (JsonNode resource : exported.required("deployment").required("resources")) {
      if ("pulumi:pulumi:Stack".equals(resource.path("type").asText())) {
        return resource.required("outputs");
      }
    }
    throw new AssertionError("export did not contain a root stack resource: " + exported);
  }

  private static JsonNode normalizeExport(JsonNode exported) {
    ObjectNode normalized = exported.deepCopy();
    JsonNode deployment = normalized.get("deployment");
    if (deployment instanceof ObjectNode object) {
      object.remove("manifest");
      JsonNode metadata = object.get("metadata");
      if (metadata != null && metadata.isObject() && metadata.isEmpty()) {
        object.remove("metadata");
      }
    }
    return normalized;
  }

  private static void assertContains(String actual, String expected) {
    assertTrue(
        actual.contains(expected), () -> "expected output to contain " + expected + ":\n" + actual);
  }

  private static List<String> stackNames(JsonNode stacksJson) {
    return stacksJson.findValuesAsText("name");
  }

  private void seedAcmeIdentity() {
    cleanupAcmeIdentity();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Organization org = new Organization();
              org.name = ORG;
              org.displayName = ORG;
              org.persistAndFlush();

              User user = new User();
              user.iss = "local:pulumi-cli-smoke";
              user.sub = ORG;
              user.email = "acme@example.com";
              user.displayName = "Acme Test User";
              user.persistAndFlush();

              OrgMember member = new OrgMember();
              member.orgId = org.id;
              member.userId = user.id;
              member.role = OrgMember.Role.owner;
              member.persistAndFlush();

              ApiToken token = new ApiToken();
              token.tokenHash = TestIdentity.sha256Hex(RAW_TOKEN);
              token.userId = user.id;
              token.orgId = org.id;
              token.kind = ApiToken.Kind.service_account;
              token.description = "pulumi cli smoke fixture";
              token.persistAndFlush();
            });
  }

  private void cleanupAcmeIdentity() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              em.createNativeQuery("DELETE FROM organizations WHERE name = ?1")
                  .setParameter(1, ORG)
                  .executeUpdate();
              em.createNativeQuery("DELETE FROM users WHERE iss = ?1 AND sub = ?2")
                  .setParameter(1, "local:pulumi-cli-smoke")
                  .setParameter(2, ORG)
                  .executeUpdate();
            });
  }
}
