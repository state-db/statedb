package app.statedb.persistence.identity;

import java.util.UUID;

/** Grouped row count for one organization in an inventory query. */
public record OrganizationAggregateCount(UUID orgId, long total) {}
