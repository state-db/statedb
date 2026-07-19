package io.statedb.consoleapi.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.authz.CurrentPrincipal;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.Stack;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackState;
import io.statedb.persistence.stack.WorkspaceListFinder;
import io.statedb.persistence.stack.WorkspaceSummary;
import io.statedb.persistence.update.ConsoleUpdateHistoryFinder;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/workspaces")
@SessionPrincipal
public class WorkspacesResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject WorkspaceListFinder workspaceListFinder;

  @Inject StackAssembler stackAssembler;

  @Inject ConsoleUpdateHistoryFinder updateHistoryFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public List<WorkspaceResponse> list(
      @QueryParam("org") String org, @QueryParam("project") String project) {
    AuthenticatedPrincipal principal = currentPrincipal.get();
    // The finder's predicate mirrors the Cedar read policies (role floor + visibility + shares),
    // so the console lists exactly the stacks the principal could open.
    List<WorkspaceSummary> summaries =
        workspaceListFinder.findVisibleToUser(principal.userId(), org, project);
    if ((org != null || project != null) && summaries.isEmpty()) {
      throw new NotFoundException();
    }
    return summaries.stream().map(WorkspacesResource::toResponse).toList();
  }

  @GET
  @Path("/{org}/{project}/{stack}")
  @Produces(MediaType.APPLICATION_JSON)
  public StackDetailResponse get(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    WorkspaceSummary summary = visibleStack(org, project, stack);
    AssembledDeployment assembled = stackAssembler.assemble(summary.stackId());
    JsonNode resources = assembled.deployment().path("resources");
    Stack stackEntity = Stack.findById(summary.stackId());
    StackState state = StackState.find("stackId", summary.stackId()).singleResult();
    return new StackDetailResponse(
        summary.stackId(),
        stackEntity.workspaceId,
        stackEntity.createdAt,
        summary.orgName(),
        summary.projectName(),
        summary.stackName(),
        summary.version(),
        summary.resourceCount(),
        summary.lastUpdate() == null
            ? null
            : new LastUpdateResponse(
                summary.lastUpdate().kind(),
                summary.lastUpdate().status(),
                summary.lastUpdate().startedAt(),
                summary.lastUpdate().version()),
        summary.lock() == null
            ? null
            : new LockResponse(summary.lock().lockedBy(), summary.lock().lockedAt()),
        resources.isArray() ? resources : JsonNodeFactory.instance.arrayNode(),
        assembled.deployment().path("manifest").path("version").textValue(),
        stackEntity.tags == null ? Map.of() : stackEntity.tags,
        state.baseSnapshotVersion,
        state.journalEntriesSinceCompaction,
        state.autoCompactThreshold);
  }

  @GET
  @Path("/{org}/{project}/{stack}/export")
  @Produces(MediaType.APPLICATION_JSON)
  public ExportResponse export(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    WorkspaceSummary summary = visibleStack(org, project, stack);
    AssembledDeployment assembled = stackAssembler.assemble(summary.stackId());
    return new ExportResponse(assembled.version(), assembled.deployment());
  }

  @GET
  @Path("/{org}/{project}/{stack}/updates")
  @Produces(MediaType.APPLICATION_JSON)
  public UpdateHistoryResponse updates(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @QueryParam("page") Integer requestedPage,
      @QueryParam("kind") String requestedKind,
      @QueryParam("status") String requestedStatus) {
    WorkspaceSummary workspace = visibleStack(org, project, stack);
    int page = requestedPage == null ? 1 : requestedPage;
    if (page < 1) {
      throw new BadRequestException("page must be positive");
    }
    StackUpdate.Kind kind = parseEnum(StackUpdate.Kind.class, requestedKind, "kind");
    StackUpdate.Status status = parseEnum(StackUpdate.Status.class, requestedStatus, "status");
    int pageSize = 50;
    var history = updateHistoryFinder.find(workspace.stackId(), kind, status, page, pageSize);
    List<UpdateHistoryRow> rows =
        history.updates().stream()
            .map(
                update ->
                    new UpdateHistoryRow(
                        update.id,
                        update.dryRun ? null : update.version,
                        update.kind.wireValue(),
                        update.status.wireValue(),
                        update.message != null ? update.message : update.description,
                        history.summaries().get(update.id),
                        update.startedAt,
                        update.endedAt))
            .toList();
    return new UpdateHistoryResponse(rows, page, pageSize, history.total());
  }

  private static <E extends Enum<E>> E parseEnum(
      Class<E> type, String value, String parameterName) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value.replace('-', '_').toUpperCase());
    } catch (IllegalArgumentException ignored) {
      throw new BadRequestException("invalid " + parameterName);
    }
  }

  private WorkspaceSummary visibleStack(String org, String project, String stack) {
    UUID userId = currentPrincipal.get().userId();
    return workspaceListFinder.findVisibleToUser(userId, org, project).stream()
        .filter(candidate -> candidate.stackName().equals(stack))
        .findFirst()
        .orElseThrow(NotFoundException::new);
  }

  private static WorkspaceResponse toResponse(WorkspaceSummary summary) {
    return new WorkspaceResponse(
        summary.stackId(),
        summary.workspaceKind().name(),
        summary.orgName(),
        summary.projectName(),
        summary.projectCreatedAt(),
        summary.stackName(),
        summary.version(),
        summary.resourceCount(),
        summary.lastUpdate() == null
            ? null
            : new LastUpdateResponse(
                summary.lastUpdate().kind(),
                summary.lastUpdate().status(),
                summary.lastUpdate().startedAt(),
                summary.lastUpdate().version()),
        summary.lock() == null
            ? null
            : new LockResponse(summary.lock().lockedBy(), summary.lock().lockedAt()));
  }

  public record WorkspaceResponse(
      UUID id,
      String kind,
      String org,
      String project,
      Instant projectCreatedAt,
      String stack,
      int version,
      Long resourceCount,
      LastUpdateResponse lastUpdate,
      LockResponse lock) {}

  public record LastUpdateResponse(String kind, String status, Instant startedAt, int version) {}

  public record LockResponse(String lockedBy, Instant lockedAt) {}

  public record StackDetailResponse(
      UUID id,
      UUID workspaceId,
      Instant createdAt,
      String org,
      String project,
      String name,
      int version,
      Long resourceCount,
      LastUpdateResponse lastUpdate,
      LockResponse lock,
      JsonNode resources,
      String pulumiVersion,
      Map<String, String> tags,
      int baseSnapshotVersion,
      int journalEntriesSinceCompaction,
      int autoCompactThreshold) {}

  public record ExportResponse(int version, JsonNode deployment) {}

  public record UpdateHistoryResponse(
      List<UpdateHistoryRow> updates, int page, int pageSize, long total) {}

  public record UpdateHistoryRow(
      UUID id,
      Integer version,
      String kind,
      String status,
      String message,
      JsonNode summary,
      Instant startedAt,
      Instant endedAt) {}
}
