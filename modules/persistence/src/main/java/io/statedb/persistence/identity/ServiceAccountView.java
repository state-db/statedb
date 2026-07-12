package io.statedb.persistence.identity;

import java.time.Instant;
import java.util.UUID;

/** One org service account (machine identity) with its token's lifecycle timestamps. */
public record ServiceAccountView(
    UUID id, String name, Instant createdAt, Instant tokenExpiresAt, Instant tokenLastUsedAt) {}
