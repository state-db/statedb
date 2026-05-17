package io.statedb.persistence.identity;

import java.util.UUID;

public record AuthenticatedPrincipal(UUID userId, UUID orgId, String orgName, ApiToken.Kind kind) {}
