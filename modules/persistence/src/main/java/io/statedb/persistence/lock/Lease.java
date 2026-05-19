package io.statedb.persistence.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Lease(UUID lockToken, Duration duration, Instant expiresAt) {}
