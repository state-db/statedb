package io.statedb.pulumiclismoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;

/**
 * Smoke coverage for the read-only commands that ask the backend "what is out there?" — the ones
 * whose answers span more than the current project.
 *
 * <pre>
 *   about                                          reportsBackendAndListsAcrossProjects
 *   project ls                                     reportsBackendAndListsAcrossProjects
 *   stack ls --all / --organization                reportsBackendAndListsAcrossProjects
 *   logs                                           reportsBackendAndListsAcrossProjects
 *   console                                        reportsBackendAndListsAcrossProjects
 * </pre>
 *
 * <p>Two projects share one container so the cross-project listings have something to distinguish:
 * plain {@code stack ls} filters server-side by project, while {@code --all} must not.
 */
@QuarkusTest
class BackendInspectionSmokeIT extends PulumiSmokeBase {

  private static final String PROJECT_A = "inspect-a";
  private static final String PROJECT_B = "inspect-b";
  private static final String WORKSPACE_B = "/workspace-b";

  @Test
  void reportsBackendAndListsAcrossProjects() throws Exception {
    String backendUrl = exposeBackend();
    pulumi = singleProjectContainer(project(PROJECT_A));
    pulumi.start();

    // Second project, created after start so this test does not need its own container plumbing.
    pulumi.execInContainer("sh", "-lc", "mkdir -p " + WORKSPACE_B);
    pulumi.copyFileToContainer(Transferable.of(project(PROJECT_B)), WORKSPACE_B + "/Pulumi.yaml");
    pulumi.copyFileToContainer(Transferable.of("{}\n"), WORKSPACE_B + "/Pulumi.dev.yaml");

    execResult("pulumi login " + backendUrl);
    execResult("pulumi stack init " + STACK);
    execResult("pulumi up --yes --non-interactive --skip-preview");

    execResult(WORKSPACE_B, "pulumi stack init " + STACK);
    execResult(WORKSPACE_B, "pulumi up --yes --non-interactive --skip-preview");

    // about reports the backend it is logged into (in `name`; `url` is the web console's address,
    // which a state backend does not advertise) and resolves the current stack through GET stack +
    // GET export. An empty `errors` array is the signal that none of that failed.
    JsonNode about = execJson("pulumi about --json");
    JsonNode backend = about.required("backend");
    assertContains(backend.required("name").asText(), "host.testcontainers.internal");
    assertEquals(ORG, backend.required("user").asText());
    assertEquals(ORG, backend.required("organizations").get(0).asText());

    JsonNode currentStack = about.required("currentStack");
    assertEquals(
        ORG + "/" + PROJECT_A + "/" + STACK, currentStack.required("fullyQualifiedName").asText());
    assertTrue(
        currentStack.required("resources").size() > 0,
        () -> "about should list the exported resources:\n" + currentStack);
    assertTrue(
        about.required("errors").isEmpty(), () -> "about reported errors:\n" + about.get("errors"));

    // project ls spans the org, so it must see both projects.
    String projects = execResult("pulumi project ls --json").getStdout();
    assertContains(projects, PROJECT_A);
    assertContains(projects, PROJECT_B);

    // Plain `stack ls` is project-scoped — the CLI sends ?project= and we filter server-side, so
    // only this project's stack comes back. --all is what drops that filter (--organization narrows
    // the result client-side, it does not widen it). In the wide listings the CLI qualifies every
    // name except the currently-selected stack's, which stays bare.
    JsonNode scoped = execJson("pulumi stack ls --json");
    assertEquals(
        List.of(STACK), stackNames(scoped), () -> "stack ls should be project-scoped:\n" + scoped);

    List<String> wide = List.of(qualified(PROJECT_B), STACK);
    JsonNode all = execJson("pulumi stack ls --all --json");
    assertEquals(
        wide,
        stackNames(all).stream().sorted().toList(),
        () -> "stack ls --all should span both projects:\n" + all);

    JsonNode byOrg = execJson("pulumi stack ls --all --organization " + ORG + " --json");
    assertEquals(
        wide,
        stackNames(byOrg).stream().sorted().toList(),
        () -> "stack ls --all --organization should span both projects:\n" + byOrg);

    // logs reaches the backend only to export the checkpoint; the resources here produce no logs,
    // so the command's contract with us is simply that the export succeeds.
    execResult("pulumi logs --json");

    // console resolves the stack server-side and then hands a URL to a web browser. The container
    // has no browser, so tolerate that failure — but not a backend one.
    Container.ExecResult console = execAllowingFailure("pulumi console");
    String consoleOutput = console.getStdout() + console.getStderr();
    assertTrue(
        console.getExitCode() == 0 || consoleOutput.contains("browser"),
        () ->
            "console should resolve the stack and only fail on opening a browser:\n"
                + consoleOutput);
  }

  private static String qualified(String project) {
    return ORG + "/" + project + "/" + STACK;
  }

  private static String project(String name) {
    return """
        name: %s
        runtime: yaml
        description: Smoke project for backend inspection commands.
        outputs:
          plain: %s
        """
        .formatted(name, PLAIN_VALUE);
  }
}
