package io.statedb.persistence.update;

/** Panache projection of {@link StackUpdate#baseSnapshotVersion}. */
public record LineageSnapshotVersion(Integer baseSnapshotVersion) {}
