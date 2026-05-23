package io.statedb.persistence.auth;

import java.util.UUID;

public record AuthenticatedPrincipal(
    UUID userId, UUID orgId, String orgName, ApiToken.Kind kind, UUID apiTokenId) {}
