package io.statedb.persistence.auth;

import io.statedb.persistence.identity.OrgMember;
import java.util.UUID;

public record AuthenticatedPrincipal(
    UUID userId,
    String iss,
    String sub,
    UUID orgId,
    String orgName,
    OrgMember.Role role,
    ApiToken.Kind kind,
    UUID apiTokenId) {}
