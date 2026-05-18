package io.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.testsupport.PulumiProject;
import io.statedb.testsupport.RepoRoot;
import io.statedb.testsupport.TestIdentity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URL;
import java.time.Duration;
import java.util.List;
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

  private static final String STACK = "dev";
  private static final String RAW_TOKEN = "pulumi-cli-smoke-token";

  @TestHTTPResource("/")
  URL baseUrl;

  @Inject EntityManager em;

  private GenericContainer<?> pulumi;

  @BeforeEach
  void seed() {
    TestIdentity.seed(em, RAW_TOKEN);
  }

  @AfterEach
  void stopContainerAndCleanup() {
    if (pulumi != null) {
      pulumi.stop();
    }
    TestIdentity.cleanup(em);
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

  private JsonNode execJson(String command) throws Exception {
    return JSON.readTree(execResult(command).getStdout());
  }

  private Container.ExecResult execResult(String command) throws Exception {
    Container.ExecResult result =
        pulumi.execInContainer("sh", "-lc", "cd /workspace && " + command);
    assertEquals(
        0,
        result.getExitCode(),
        () -> command + "\nstdout:\n" + result.getStdout() + "\nstderr:\n" + result.getStderr());
    return result;
  }

  private static List<String> stackNames(JsonNode stacksJson) {
    return stacksJson.findValuesAsText("name");
  }
}
