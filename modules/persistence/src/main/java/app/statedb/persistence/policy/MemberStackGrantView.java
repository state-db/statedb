package app.statedb.persistence.policy;

import java.time.Instant;
import java.util.UUID;

/** One organization member's stack-scoped policy grant with display names resolved. */
public record MemberStackGrantView(
    UUID principalUserId,
    UUID stackId,
    String projectName,
    String stackName,
    String templateId,
    Instant createdAt) {}
