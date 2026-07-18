package io.statedb.persistence.stack;

import java.util.UUID;

/** Panache projection of {@link Snapshot} that skips hydrating the checkpoint JSONB. */
public record SnapshotResourceCount(UUID stackId, int resourceCount) {}
