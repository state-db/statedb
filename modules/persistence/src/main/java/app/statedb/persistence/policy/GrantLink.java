package app.statedb.persistence.policy;

import java.util.UUID;

/**
 * Flattened {@code policy_grants} row with the names needed to build a Cedar {@code TemplateLink}.
 * The stack columns are null for managed (per-org) grants; present for per-stack shares.
 */
public record GrantLink(
    UUID id,
    String templateId,
    UUID principalUserId,
    String orgName,
    String stackOrg,
    String stackProject,
    String stackName) {}
