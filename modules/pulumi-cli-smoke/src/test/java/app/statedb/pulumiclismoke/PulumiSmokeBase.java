package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import app.statedb.persistence.auth.ApiToken;
import app.statedb.persistence.identity.OrgMember;
import app.statedb.persistence.identity.Organization;
import app.statedb.persistence.identity.ServiceAccountCreator;
import app.statedb.persistence.identity.User;
import app.statedb.testsupport.PulumiProject;
import app.statedb.testsupport.RepoRoot;
import app.statedb.testsupport.TestIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared harness for the {@code *SmokeIT} classes. Seeds the {@code acme} org + token, manages the
 * per-test Pulumi container lifecycle, and exposes the container/exec/JSON helpers every smoke test
 * needs. Not a test itself — no {@code IT} suffix, so failsafe's {@code **}{@code /*IT.java}
 * include skips it, and it is {@code abstract} anyway.
 *
 * <p>To add smoke coverage: pick the command-family {@code *SmokeIT} subclass (or add a new one),
 * extend this, and add a {@code @Test}. Group related assertions into one method — each method
 * spins its own container, so fewer methods means fewer Docker starts. See {@code README.md}.
 */
public abstract class PulumiSmokeBase {

  protected static final ObjectMapper JSON = new ObjectMapper();

  // Overridable so the nightly CI matrix can run the suite against several CLI versions; the
  // default pin comes from the pom's pulumi.cli.image property (forwarded by failsafe).
  protected static final DockerImageName PULUMI_IMAGE =
      DockerImageName.parse(System.getProperty("pulumi.cli.image", "pulumi/pulumi:3.234.0"));

  protected static final String ORG = "acme";
  protected static final String STACK = "dev";
  protected static final String RAW_TOKEN = "pulumi-cli-smoke-token";
  // Shared by the StackReference producer outputs and the inline inspection project.
  protected static final String PLAIN_VALUE = "plain-from-producer";
  protected static final String SECRET_VALUE = "secret-from-producer";

  @TestHTTPResource("/")
  URL baseUrl;

  @Inject EntityManager em;

  @Inject ServiceAccountCreator serviceAccountCreator;

  protected GenericContainer<?> pulumi;
  protected String rawToken = RAW_TOKEN;
  private UUID serviceAccountId;

  protected boolean useServiceAccountLifecycleCredential() {
    return false;
  }

  // Protected (not package-private) so JUnit still discovers these lifecycle methods from
  // subclasses in the faults subpackage.
  @BeforeEach
  protected void seedIdentity() {
    seedAcmeIdentity();
  }

  @AfterEach
  protected void stopContainerAndCleanup() {
    if (pulumi != null) {
      pulumi.stop();
    }
    cleanupAcmeIdentity();
  }

  // Exposes the running app to the container network and returns the backend URL the CLI logs into.
  protected String exposeBackend() {
    Testcontainers.exposeHostPorts(baseUrl.getPort());
    return "http://host.testcontainers.internal:" + baseUrl.getPort() + "/pulumi";
  }

  // Bare container with the shared Pulumi env and an sh entrypoint; callers add project files and a
  // command.
  protected GenericContainer<?> baseContainer() {
    return new GenericContainer<>(PULUMI_IMAGE)
        // The task adapter pre-pulls this 3 GB image once. Registry-backed default policies made
        // Testcontainers re-download it for every test container and exhausted the old 90-minute
        // hosted job without ever starting a test.
        .withImagePullPolicy(imageName -> false)
        .withEnv("PULUMI_ACCESS_TOKEN", rawToken)
        .withEnv("PULUMI_CONFIG_PASSPHRASE", "test")
        .withEnv("PULUMI_HOME", "/tmp/pulumi-home")
        .withEnv("PULUMI_SKIP_CONFIRMATIONS", "true")
        .withEnv("PULUMI_SKIP_UPDATE_CHECK", "true")
        .withEnv("PULUMI_COLOR", "never")
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
        .withStartupTimeout(Duration.ofMinutes(2));
  }

  // Container with the recorder project (random provider) mounted at /workspace.
  protected GenericContainer<?> recorderProjectContainer() {
    MountableFile recorderProject =
        MountableFile.forHostPath(RepoRoot.get().resolve(PulumiProject.RECORDER_PROJECT_PATH));
    return baseContainer()
        .withCopyFileToContainer(recorderProject, "/workspace/Pulumi.yaml")
        .withCopyToContainer(Transferable.of("{}\n"), "/workspace/Pulumi.dev.yaml")
        .withCommand("-lc", "mkdir -p /tmp/pulumi-home && sleep infinity");
  }

  // Container with an inline single-project body mounted at /workspace.
  protected GenericContainer<?> singleProjectContainer(String pulumiYaml) {
    return baseContainer()
        .withCopyToContainer(Transferable.of(pulumiYaml), "/workspace/Pulumi.yaml")
        .withCopyToContainer(Transferable.of("{}\n"), "/workspace/Pulumi.dev.yaml")
        .withCommand("-lc", "mkdir -p /tmp/pulumi-home && sleep infinity");
  }

  protected JsonNode execJson(String command) throws Exception {
    return JSON.readTree(execResult(command).getStdout());
  }

  protected JsonNode execJson(String workdir, String command) throws Exception {
    return JSON.readTree(execResult(workdir, command).getStdout());
  }

  protected Container.ExecResult execResult(String command) throws Exception {
    return execResult("/workspace", command);
  }

  protected Container.ExecResult execResult(String workdir, String command) throws Exception {
    Container.ExecResult result =
        pulumi.execInContainer("sh", "-lc", "cd " + workdir + " && " + command);
    assertEquals(
        0,
        result.getExitCode(),
        () -> command + "\nstdout:\n" + result.getStdout() + "\nstderr:\n" + result.getStderr());
    return result;
  }

  // Like execResult but tolerates a non-zero exit, for commands we expect the CLI to reject (e.g.
  // the unsupported schedule/webhook 501s). The caller asserts on the exit code.
  protected Container.ExecResult execAllowingFailure(String command) throws Exception {
    return pulumi.execInContainer("sh", "-lc", "cd /workspace && " + command);
  }

  /** Runs a CLI command detached; the exec returns immediately while the command keeps running. */
  protected void execDetached(String command) throws Exception {
    pulumi.execInContainer(
        "sh", "-lc", "cd /workspace && nohup " + command + " > /tmp/detached.log 2>&1 &");
  }

  protected void awaitTrue(String what, Duration timeout, Callable<Boolean> condition)
      throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (Boolean.TRUE.equals(condition.call())) {
        return;
      }
      Thread.sleep(200);
    }
    fail("timed out after " + timeout + " waiting for: " + what);
  }

  protected static void assertContains(String actual, String expected) {
    assertTrue(
        actual.contains(expected), () -> "expected output to contain " + expected + ":\n" + actual);
  }

  protected static List<String> stackNames(JsonNode stacksJson) {
    return stacksJson.findValuesAsText("name");
  }

  /** The exported resource with the given Pulumi type token and name, or empty if it is gone. */
  protected static Optional<JsonNode> findResource(JsonNode exported, String type, String name) {
    for (JsonNode resource : exported.required("deployment").required("resources")) {
      if (type.equals(resource.path("type").asText())
          && resource.path("urn").asText().endsWith("::" + name)) {
        return Optional.of(resource);
      }
    }
    return Optional.empty();
  }

  protected static JsonNode requireResource(JsonNode exported, String type, String name) {
    return findResource(exported, type, name)
        .orElseThrow(
            () ->
                new AssertionError("export has no " + type + " named " + name + ":\n" + exported));
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

              if (useServiceAccountLifecycleCredential()) {
                ServiceAccountCreator.MintedServiceAccount account =
                    serviceAccountCreator.create(
                        org.id, "Acme Test User", 30, ApiToken.AccessProfile.stack_lifecycle, null);
                serviceAccountId = account.accountId();
                rawToken = account.rawToken();
                return;
              }

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
              token.kind = ApiToken.Kind.oidc;
              token.orgId = null;
              token.expiresAt = java.time.Instant.now().plusSeconds(60 * 60);
              token.description = "pulumi cli smoke fixture";
              token.persistAndFlush();
            });
  }

  private void cleanupAcmeIdentity() {
    UUID accountId = serviceAccountId;
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
              if (accountId != null) {
                User.deleteById(accountId);
              }
            });
    serviceAccountId = null;
    rawToken = RAW_TOKEN;
  }
}
