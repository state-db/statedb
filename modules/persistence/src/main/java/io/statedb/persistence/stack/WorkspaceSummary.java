package io.statedb.persistence.stack;

import java.time.Instant;
import java.util.UUID;

/** Console-facing workspace row assembled from stack metadata plus optional live status. */
public record WorkspaceSummary(
    UUID stackId,
    UUID orgId,
    String orgName,
    String projectName,
    String stackName,
    Workspace.Kind workspaceKind,
    int version,
    Long resourceCount,
    LastUpdate lastUpdate,
    Lock lock) {

  public record LastUpdate(String kind, String status, Instant startedAt, int version) {}

  public record Lock(String lockedBy, Instant lockedAt) {}
}
