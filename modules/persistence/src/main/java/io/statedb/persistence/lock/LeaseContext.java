package io.statedb.persistence.lock;

import java.util.UUID;

/**
 * Identity of a validated lease as seen by downstream handlers. No token material lives here;
 * {@code leaseId} is the surrogate PK of the lock row and is the stable handle for revalidation,
 * renewal, and cancellation.
 */
public record LeaseContext(UUID leaseId, UUID stackId, UUID updateId) {}
