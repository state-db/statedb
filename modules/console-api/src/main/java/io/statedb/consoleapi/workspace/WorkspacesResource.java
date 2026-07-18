package io.statedb.consoleapi.workspace;

import io.statedb.consoleapi.auth.SessionPrincipal;
import io.statedb.persistence.auth.AuthenticatedPrincipal;
import io.statedb.persistence.authz.CurrentPrincipal;
import io.statedb.persistence.stack.WorkspaceListFinder;
import io.statedb.persistence.stack.WorkspaceSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/api/workspaces")
@SessionPrincipal
public class WorkspacesResource {

  @Inject CurrentPrincipal currentPrincipal;

  @Inject WorkspaceListFinder workspaceListFinder;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public List<WorkspaceResponse> list() {
    AuthenticatedPrincipal principal = currentPrincipal.get();
    // The finder's predicate mirrors the Cedar read policies (role floor + visibility + shares),
    // so the console lists exactly the stacks the principal could open.
    return workspaceListFinder.findVisibleToUser(principal.userId()).stream()
        .map(WorkspacesResource::toResponse)
        .toList();
  }

  private static WorkspaceResponse toResponse(WorkspaceSummary summary) {
    return new WorkspaceResponse(
        summary.stackId(),
        summary.workspaceKind().name(),
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
            : new LockResponse(summary.lock().lockedBy(), summary.lock().lockedAt()));
  }

  public record WorkspaceResponse(
      UUID id,
      String kind,
      String org,
      String project,
      String stack,
      int version,
      Long resourceCount,
      LastUpdateResponse lastUpdate,
      LockResponse lock) {}

  public record LastUpdateResponse(String kind, String status, Instant startedAt, int version) {}

  public record LockResponse(String lockedBy, Instant lockedAt) {}
}
