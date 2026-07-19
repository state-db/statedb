package io.statedb.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.statedb.persistence.events.UpdateEvent;
import io.statedb.persistence.identity.Organization;
import io.statedb.persistence.stack.Project;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.Workspace;
import io.statedb.persistence.update.ConsoleUpdateHistoryFinder;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ConsoleUpdateHistoryFinderTest {

  @Inject ConsoleUpdateHistoryFinder finder;

  @Test
  @TestTransaction
  void includesPreviewsAndCombinesFiltersAndPaginationWithSummaryEvents() {
    Stack stack = stack();
    StackUpdate oldUpdate =
        update(
            stack,
            StackUpdate.Kind.UPDATE,
            StackUpdate.Status.SUCCEEDED,
            1,
            "2026-07-18T10:00:00Z");
    StackUpdate preview =
        update(
            stack,
            StackUpdate.Kind.PREVIEW,
            StackUpdate.Status.SUCCEEDED,
            0,
            "2026-07-18T11:00:00Z");
    update(stack, StackUpdate.Kind.PREVIEW, StackUpdate.Status.FAILED, 0, "2026-07-18T12:00:00Z");
    summary(preview);

    var firstPage = finder.find(stack.id, null, null, 1, 2);
    assertEquals(3, firstPage.total());
    assertEquals(StackUpdate.Status.FAILED, firstPage.updates().get(0).status);
    assertEquals(preview.id, firstPage.updates().get(1).id);
    assertEquals(
        3, firstPage.summaries().get(preview.id).path("resourceChanges").path("create").asInt());

    var filtered =
        finder.find(stack.id, StackUpdate.Kind.PREVIEW, StackUpdate.Status.SUCCEEDED, 1, 50);
    assertEquals(1, filtered.total());
    assertEquals(preview.id, filtered.updates().get(0).id);

    assertEquals(oldUpdate.id, finder.findUpdate(stack.id, 1).orElseThrow().id);
    assertEquals(preview.id, finder.findPreview(stack.id, preview.id).orElseThrow().id);
    assertEquals(1, finder.events(stack.id, preview.id, 0, 50).size());
    assertEquals(0, finder.events(stack.id, preview.id, 1, 50).size());

    var secondPage = finder.find(stack.id, null, null, 2, 2);
    assertEquals(oldUpdate.id, secondPage.updates().get(0).id);
  }

  private static Stack stack() {
    Organization org = new Organization();
    org.name = "activity-" + System.nanoTime();
    org.displayName = "Activity";
    org.persist();
    Project project = new Project();
    project.orgId = org.id;
    project.name = "project";
    project.persist();
    Workspace workspace = new Workspace();
    workspace.orgId = org.id;
    workspace.kind = Workspace.Kind.pulumi;
    workspace.persist();
    Stack stack = new Stack();
    stack.orgId = org.id;
    stack.projectId = project.id;
    stack.workspaceId = workspace.id;
    stack.name = "dev";
    stack.persist();
    return stack;
  }

  private static StackUpdate update(
      Stack stack,
      StackUpdate.Kind kind,
      StackUpdate.Status status,
      int version,
      String startedAt) {
    StackUpdate update = new StackUpdate();
    update.stackId = stack.id;
    update.kind = kind;
    update.status = status;
    update.version = version;
    update.dryRun = kind == StackUpdate.Kind.PREVIEW;
    update.startedAt = Instant.parse(startedAt);
    update.persist();
    return update;
  }

  private static void summary(StackUpdate update) {
    UpdateEvent event = new UpdateEvent();
    event.stackId = update.stackId;
    event.updateId = update.id;
    event.sequence = 1;
    event.kind = "summary";
    event.payload =
        JsonNodeFactory.instance
            .objectNode()
            .set(
                "summaryEvent",
                JsonNodeFactory.instance
                    .objectNode()
                    .set(
                        "resourceChanges", JsonNodeFactory.instance.objectNode().put("create", 3)));
    event.persist();
  }
}
