package io.statedb.persistence.stack;

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
      String kind, String status, Instant startedAt, int version, Actor actor) {}

  public record Actor(String displayName, String email, String fallback) {}

  public record Lock(UUID id, String lockedBy, Instant lockedAt) {}
}
