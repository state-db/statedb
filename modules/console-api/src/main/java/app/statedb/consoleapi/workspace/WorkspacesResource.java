package app.statedb.consoleapi.workspace;

import app.statedb.consoleapi.auth.SessionPrincipal;
import app.statedb.persistence.authz.CurrentPrincipal;
import app.statedb.persistence.stack.AssembledDeployment;
import app.statedb.persistence.stack.Stack;
import app.statedb.persistence.stack.StackAssembler;
import app.statedb.persistence.stack.StackListFinder;
import app.statedb.persistence.stack.StackState;
import app.statedb.persistence.stack.StackSummary;
import app.statedb.persistence.stack.WorkspaceListFinder;
import app.statedb.persistence.stack.WorkspaceSummary;
import app.statedb.persistence.update.ConsoleUpdateHistoryFinder;
import app.statedb.persistence.update.StackUpdate;
import app.statedb.persistence.update.UpdateActorAttribution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

  private static final int PAGE_SIZE = 50;

  @Inject CurrentPrincipal currentPrincipal;

  @Inject WorkspaceListFinder workspaceListFinder;

  @Inject StackListFinder stackListFinder;

  @Inject StackAssembler stackAssembler;

  @Inject ConsoleUpdateHistoryFinder updateHistoryFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public WorkspaceListResponse list(
      @QueryParam("org") String org,
      @QueryParam("project") String project,
      @QueryParam("search") String search,
      @QueryParam("status") String status,
      @QueryParam("page") Integer requestedPage) {
    UUID userId = currentPrincipal.get().userId();

    if (project != null) {
      List<WorkspaceSummary> summaries =
          workspaceListFinder.findVisibleToUser(userId, org, project);
      if (summaries.isEmpty()) {
        throw new NotFoundException();
      }
      List<WorkspaceResponse> items =
          summaries.stream().map(WorkspacesResource::toResponse).toList();
      return new WorkspaceListResponse(items, 1, items.size(), items.size());
    }

    int page = requestedPage == null ? 1 : Math.max(1, requestedPage);
    String statusFilter = normalizeStatus(status);
    WorkspaceListFinder.PagedWorkspaces paged =
        workspaceListFinder.findVisibleToUserPaged(
            userId, org, search, statusFilter, page, PAGE_SIZE);
    if (org != null && search == null && statusFilter == null && paged.total() == 0) {
      throw new NotFoundException();
    }
    List<WorkspaceResponse> items =
        paged.items().stream().map(WorkspacesResource::toResponse).toList();
    return new WorkspaceListResponse(items, paged.page(), PAGE_SIZE, paged.total());
  }

  @GET
  @Path("/summary")
  @Produces(MediaType.APPLICATION_JSON)
  public WorkspaceSummaryResponse summary(@QueryParam("org") String org) {
    WorkspaceListFinder.WorkspaceOverview overview =
        workspaceListFinder.overview(currentPrincipal.get().userId(), org);
    return new WorkspaceSummaryResponse(
        overview.stacks(),
        overview.projects(),
        overview.resources(),
        overview.failed(),
        overview.running());
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank() || "all".equals(status)) {
      return null;
    }
    if (!status.equals("running") && !status.equals("failed")) {
      throw new BadRequestException("invalid status");
    }
    return status;
  }

  @GET
  @Path("/{org}/{project}/{stack}")
  @Produces(MediaType.APPLICATION_JSON)
  public StackDetailResponse get(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    WorkspaceSummary summary = workspaceListFinder.detailsFor(visibleStack(org, project, stack));
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
                summary.lastUpdate().id(),
                summary.lastUpdate().kind(),
                summary.lastUpdate().status(),
                summary.lastUpdate().startedAt(),
                summary.lastUpdate().version(),
                toActorResponse(summary.lastUpdate().actor())),
        summary.lock() == null
            ? null
            : new LockResponse(
                summary.lock().id(), summary.lock().lockedBy(), summary.lock().lockedAt()),
        resources.isArray() ? resources : JsonNodeFactory.instance.arrayNode(),
        assembled.deployment().path("manifest").path("version").textValue(),
        stackEntity.tags == null ? Map.of() : stackEntity.tags,
        state.baseSnapshotVersion,
        state.journalEntriesSinceCompaction);
  }

  @GET
  @Path("/{org}/{project}/{stack}/export")
  @Produces(MediaType.APPLICATION_JSON)
  public ExportResponse export(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackSummary summary = visibleStack(org, project, stack);
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
    StackSummary workspace = visibleStack(org, project, stack);
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
                        toActorResponse(history.actors().get(update.id)),
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

  private StackSummary visibleStack(String org, String project, String stack) {
    UUID userId = currentPrincipal.get().userId();
    return stackListFinder
        .findVisibleToUser(userId, org, project, stack)
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
                summary.lastUpdate().id(),
                summary.lastUpdate().kind(),
                summary.lastUpdate().status(),
                summary.lastUpdate().startedAt(),
                summary.lastUpdate().version(),
                toActorResponse(summary.lastUpdate().actor())),
        summary.lock() == null
            ? null
            : new LockResponse(
                summary.lock().id(), summary.lock().lockedBy(), summary.lock().lockedAt()));
  }

  private static ActorResponse toActorResponse(UpdateActorAttribution actor) {
    return actor == null
        ? null
        : new ActorResponse(
            actor.kind(),
            actor.principalId(),
            actor.credentialId(),
            actor.displayName(),
            actor.email(),
            actor.fallback(),
            actor.accessProfile(),
            actor.revoked());
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

  public record LastUpdateResponse(
      UUID id,
      String kind,
      String status,
      Instant startedAt,
      Integer version,
      ActorResponse actor) {}

  public record ActorResponse(
      String kind,
      UUID principalId,
      UUID credentialId,
      String displayName,
      String email,
      String fallback,
      String accessProfile,
      boolean revoked) {}

  public record LockResponse(UUID lockId, String lockedBy, Instant lockedAt) {}

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
      int journalEntriesSinceCompaction) {}

  public record ExportResponse(int version, JsonNode deployment) {}

  public record UpdateHistoryResponse(
      List<UpdateHistoryRow> updates, int page, int pageSize, long total) {}

  public record WorkspaceListResponse(
      List<WorkspaceResponse> items, int page, int pageSize, long total) {}

  public record WorkspaceSummaryResponse(
      long stacks, long projects, long resources, long failed, long running) {}

  public record UpdateHistoryRow(
      UUID id,
      Integer version,
      String kind,
      String status,
      String message,
      JsonNode summary,
      ActorResponse actor,
      Instant startedAt,
      Instant endedAt) {}
}
