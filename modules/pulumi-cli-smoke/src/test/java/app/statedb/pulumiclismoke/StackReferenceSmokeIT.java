package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * Smoke coverage for {@code StackReference} resolution: a producer stack publishes plain and secret
 * outputs, a consumer stack reads them via {@code pulumi:pulumi:StackReference}, and secrets stay
 * masked everywhere except {@code --show-secrets}.
 */
@QuarkusTest
class StackReferenceSmokeIT extends PulumiSmokeBase {

  private static final String PRODUCER_PROJECT = "state-db-workbench";
  private static final String CONSUMER_PROJECT = "stackref-consumer";
  private static final String STACK_REF = ORG + "/" + PRODUCER_PROJECT + "/" + STACK;
  private static final String SECRET_SIGNATURE_KEY = "4dabf18193072939515e22adb298388d";
  private static final String SECRET_SIGNATURE_VALUE = "1b47061264138c4ac30d75fd1eb44270";

  @Test
  void preservesStackReferenceOutputsAndSecrets() throws Exception {
    String backendUrl = exposeBackend();

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

  // Two-project container: producer and consumer each get their own workspace dir.
  private GenericContainer<?> pulumiContainer(Map<String, String> files) {
    GenericContainer<?> container =
        baseContainer()
            .withCommand(
                "-lc",
                "mkdir -p /tmp/pulumi-home /workspace/producer /workspace/consumer"
                    + " && mv /producer-Pulumi.yaml /workspace/producer/Pulumi.yaml"
                    + " && mv /producer-Pulumi.dev.yaml /workspace/producer/Pulumi.dev.yaml"
                    + " && mv /consumer-Pulumi.yaml /workspace/consumer/Pulumi.yaml"
                    + " && mv /consumer-Pulumi.dev.yaml /workspace/consumer/Pulumi.dev.yaml"
                    + " && sleep infinity");
    files.forEach((path, content) -> container.withCopyToContainer(Transferable.of(content), path));
    return container;
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
}
