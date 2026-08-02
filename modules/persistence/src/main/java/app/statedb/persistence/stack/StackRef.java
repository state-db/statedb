package app.statedb.persistence.stack;

import java.util.UUID;

/**
 * Resolved stack identity: the result of looking up a {@code (org, project, stack)} name triple
 * against the stack tree. Carries every primary-key handle a downstream endpoint needs so callers
 * don't re-query the same joins.
 */
public record StackRef(
    UUID stackId,
    UUID workspaceId,
    UUID orgId,
    String orgName,
    String projectName,
    String stackName,
    int version,
    UUID activeUpdateId,
    String visibility) {}
