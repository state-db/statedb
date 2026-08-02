package app.statedb.testsupport.pulumi;

import app.statedb.testsupport.RepoRoot;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/**
 * Asserts that a deployment produced by state-db passes Pulumi's own {@code
 * snapshot.VerifyIntegrity} rules — i.e. it is a deployment the Pulumi CLI itself would agree to
 * load. Backed by {@code tools/pulumi/verify}, a small Go binary pinned to the same Pulumi SDK
 * version as the recorder fixtures, run in a lazily-started singleton container shared by the whole
 * test JVM.
 *
 * <p>Accepts either a raw {@code DeploymentV3} object or the {@code pulumi stack export} envelope.
 * Do not feed it recorder fixtures: fixture normalization rewrites resource ids inconsistently and
 * trips the unknown-provider rule (see {@code tools/pulumi/verify/README.md}). Verify live output.
 */
public final class PulumiDeploymentAssert {

  private static final String VERIFIER_CONTEXT = "tools/pulumi/verify";

  private static class Holder {
    static final GenericContainer<?> VERIFIER = start();

    private static GenericContainer<?> start() {
      GenericContainer<?> container =
          new GenericContainer<>(
              new ImageFromDockerfile("statedb-pulumi-verify:latest", false)
                  .withFileFromPath(".", RepoRoot.get().resolve(VERIFIER_CONTEXT)));
      container.start();
      return container;
    }
  }

  private PulumiDeploymentAssert() {}

  /** Fails with the violated integrity rule if Pulumi would refuse to load this deployment. */
  public static void assertIntegrityPasses(JsonNode deployment) {
    Result result = verify(deployment);
    if (result.exitCode() != 0) {
      throw new AssertionError(
          "expected deployment to pass Pulumi integrity verification but it failed:\n"
              + result.message()
              + "\ndeployment:\n"
              + deployment.toPrettyString());
    }
  }

  /** Asserts the deployment violates integrity and the failing rule mentions {@code rulePart}. */
  public static void assertIntegrityFails(JsonNode deployment, String rulePart) {
    Result result = verify(deployment);
    if (result.exitCode() != 1) {
      throw new AssertionError(
          "expected deployment to fail Pulumi integrity verification (exit 1) but got exit "
              + result.exitCode()
              + ": "
              + result.message());
    }
    if (!result.message().contains(rulePart)) {
      throw new AssertionError(
          "expected integrity failure mentioning \""
              + rulePart
              + "\" but the rule was:\n"
              + result.message());
    }
  }

  private static Result verify(JsonNode deployment) {
    String path = "/tmp/" + UUID.randomUUID() + ".json";
    try {
      Holder.VERIFIER.copyFileToContainer(
          Transferable.of(deployment.toString().getBytes(StandardCharsets.UTF_8)), path);
      Container.ExecResult exec = Holder.VERIFIER.execInContainer("verify", path);
      String message = (exec.getStdout() + exec.getStderr()).strip();
      if (exec.getExitCode() == 2) {
        throw new AssertionError("integrity verifier could not parse the deployment: " + message);
      }
      return new Result(exec.getExitCode(), message);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("could not run integrity verifier container", e);
    }
  }

  private record Result(int exitCode, String message) {}
}
