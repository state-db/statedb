package app.statedb.persistence.authz;

import java.util.Set;

public record AuthorizationDecision(boolean allowed, Set<String> matchedPolicyIds) {}
