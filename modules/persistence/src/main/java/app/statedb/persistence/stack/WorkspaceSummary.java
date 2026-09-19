package app.statedb.persistence.stack;

import app.statedb.persistence.update.UpdateActorAttribution;
import java.time.Instant;
import java.util.UUID;

/** Console-facing workspace row assembled from stack metadata plus optional live status. */
public record WorkspaceSummary(
    UUID stackId,
    String orgName,
    String projectName,
    Instant projectCreatedAt,
    String stackName,
    Workspace.Kind workspaceKind,
    int version,
    Long resourceCount,
    LastUpdate lastUpdate,
    Lock lock) {

  public record LastUpdate(
      UUID id,
      String kind,
      String status,
      Instant startedAt,
      Integer version,
      UpdateActorAttribution actor) {}

  public record Lock(UUID id, String lockedBy, Instant lockedAt) {}
}
