package io.statedb.persistence.stack;

import java.util.UUID;

/** Panache projection of {@link Snapshot} that skips hydrating checkpoint JSONB while pruning. */
public record SnapshotRetentionRow(UUID id, int version, long compactedFromJournalSeq) {}
