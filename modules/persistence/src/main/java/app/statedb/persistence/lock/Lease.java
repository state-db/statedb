package app.statedb.persistence.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of an acquire or renew. {@code plaintextToken} is the bearer the client must send back as
 * {@code Authorization: update-token <token>} on subsequent lease-scoped requests; it is returned
 * to the caller exactly once and is not persisted. {@code leaseId} is the surrogate PK of the lock
 * row.
 */
public record Lease(UUID leaseId, UUID plaintextToken, Duration duration, Instant expiresAt) {}
