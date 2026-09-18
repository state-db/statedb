package app.statedb.persistence.update;

import java.util.UUID;

/** Stable console attribution for the principal and exact credential that started an update. */
public record UpdateActorAttribution(
    String kind,
    UUID principalId,
    UUID credentialId,
    String displayName,
    String email,
    String fallback,
    String accessProfile,
    boolean revoked) {}
