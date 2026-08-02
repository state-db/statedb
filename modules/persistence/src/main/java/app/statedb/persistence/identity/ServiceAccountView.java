package app.statedb.persistence.identity;

import java.time.Instant;
import java.util.UUID;

/** One org service account with credential lifecycle and human audit attribution. */
public record ServiceAccountView(
    UUID id,
    UUID tokenId,
    String name,
    String orgName,
    String orgDisplayName,
    Instant createdAt,
    Instant tokenExpiresAt,
    Instant tokenLastUsedAt,
    Instant tokenRevokedAt,
    Instant accountDeactivatedAt,
    UUID createdById,
    String createdByName,
    String createdByEmail,
    UUID revokedById,
    String revokedByName,
    String revokedByEmail) {}
