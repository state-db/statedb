package app.statedb.persistence.operations;

import app.statedb.persistence.stack.CompactionJob;
import java.time.Instant;
import java.util.UUID;

/** Operator-facing projection of one compaction queue row. */
public record CompactionJobView(
    UUID id,
    UUID stackId,
    String org,
    String project,
    String stack,
    CompactionJob.Status status,
    int attempts,
    String errorMessage,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt) {}
