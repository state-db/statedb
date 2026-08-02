package app.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke coverage for the {@code pulumi state} editing commands. Every one of them is a whole-state
 * read-modify-write on the client: {@code GET .../export} then {@code POST .../import}. These tests
 * prove the round trip survives our export/import pair rather than re-testing the CLI's edits.
 *
 * <pre>
 *   state protect / unprotect / taint / untaint   editsResourcesInPlace
 *   state rename / delete / repair                editsResourcesInPlace
 *   state move                                    movesAResourceToAnotherStack
 * </pre>
 */
@QuarkusTest
class StateEditSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT = "state-edit";
  private static final String PET_TYPE = "random:index/randomPet:RandomPet";
  private static final String STAGING_STACK = "staging";

  @Test
  void editsResourcesInPlace() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(twoPetProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");

    String scratchUrn =
        requireResource(execJson("pulumi stack export"), PET_TYPE, "scratch")
            .required("urn")
            .asText();

    // protect / unprotect and taint / untaint each toggle one `omitempty` flag on the resource, so
    // assert the flag survived the export/import round trip rather than diffing whole nodes: every
    // import re-encrypts the resource's secret properties, so ciphertext is never byte-stable.
    execResult("pulumi state protect '" + scratchUrn + "' --yes");
    assertTrue(scratchFlag("protect"), "state protect should set protect=true");
    execResult("pulumi state unprotect '" + scratchUrn + "' --yes");
    assertFalse(scratchFlag("protect"), "state unprotect should clear protect");

    execResult("pulumi state taint '" + scratchUrn + "' --yes");
    assertTrue(scratchFlag("taint"), "state taint should set taint=true");
    execResult("pulumi state untaint '" + scratchUrn + "' --yes");
    assertFalse(scratchFlag("taint"), "state untaint should clear taint");

    // rename rewrites the URN's last segment; the resource itself survives.
    execResult("pulumi state rename '" + scratchUrn + "' renamed --yes");
    JsonNode afterRename = execJson("pulumi stack export");
    JsonNode renamed = requireResource(afterRename, PET_TYPE, "renamed");
    assertTrue(
        findResource(afterRename, PET_TYPE, "scratch").isEmpty(),
        () -> "state rename should retire the old URN:\n" + afterRename);

    // delete drops the renamed resource and leaves the untouched one behind.
    execResult("pulumi state delete '" + renamed.required("urn").asText() + "' --yes");
    JsonNode afterDelete = execJson("pulumi stack export");
    assertTrue(
        findResource(afterDelete, PET_TYPE, "renamed").isEmpty(),
        () -> "state delete should remove the resource:\n" + afterDelete);
    requireResource(afterDelete, PET_TYPE, "keep");

    // repair on a healthy checkpoint is a no-op, but it still exports and validates the state.
    execResult("pulumi state repair --yes");
    requireResource(execJson("pulumi stack export"), PET_TYPE, "keep");
  }

  @Test
  void movesAResourceToAnotherStack() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(twoPetProject());
    pulumi.start();

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");
    execResult("pulumi stack init " + STAGING_STACK);
    execResult("pulumi stack select " + STACK);

    String scratchUrn =
        requireResource(execJson("pulumi stack export"), PET_TYPE, "scratch")
            .required("urn")
            .asText();

    // move reads and rewrites both stacks' checkpoints: two exports, two imports.
    execResult(
        "pulumi state move --dest "
            + ORG
            + "/"
            + PROJECT
            + "/"
            + STAGING_STACK
            + " '"
            + scratchUrn
            + "' --yes");

    JsonNode source = execJson("pulumi stack export");
    assertTrue(
        findResource(source, PET_TYPE, "scratch").isEmpty(),
        () -> "the source stack should no longer hold the moved resource:\n" + source);
    requireResource(source, PET_TYPE, "keep");

    execResult("pulumi stack select " + STAGING_STACK);
    JsonNode destination = execJson("pulumi stack export");
    JsonNode moved = requireResource(destination, PET_TYPE, "scratch");
    assertTrue(
        moved.required("urn").asText().startsWith("urn:pulumi:" + STAGING_STACK + "::"),
        () -> "the moved resource's URN should be rewritten for the destination stack: " + moved);
  }

  private boolean scratchFlag(String field) throws Exception {
    return requireResource(execJson("pulumi stack export"), PET_TYPE, "scratch")
        .path(field)
        .asBoolean();
  }

  // Two independent resources: one to edit, one that must survive every edit untouched.
  private static String twoPetProject() {
    return """
        name: %s
        runtime: yaml
        description: Smoke project for the state editing commands.
        resources:
          keep:
            type: random:RandomPet
            properties:
              length: 2
          scratch:
            type: random:RandomPet
            properties:
              length: 2
        """
        .formatted(PROJECT);
  }
}
