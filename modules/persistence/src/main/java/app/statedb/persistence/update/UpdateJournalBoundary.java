package app.statedb.persistence.update;

/** Panache projection of {@link StackUpdate#endJournalSeq}. */
public record UpdateJournalBoundary(Long endJournalSeq) {}
