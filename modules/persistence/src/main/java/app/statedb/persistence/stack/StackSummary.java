package app.statedb.persistence.stack;

import java.time.Instant;
import java.util.UUID;

/**
 * Row projection for stack-list endpoints. Backs Pulumi's {@code GET /api/user/stacks} and the
 * console workspace listing — each HTTP module maps to its own wire DTO.
 *
 * <p>{@code lastUpdate} and {@code resourceCount} are nullable placeholders so the Pulumi DTO can
 * omit them via {@code JsonInclude.NON_NULL} until the {@code stack_updates} table and the
 * journal-driven resource count land.
 */
public record StackSummary(
    UUID stackId,
    UUID orgId,
    String orgName,
    String projectName,
    Instant projectCreatedAt,
    String stackName,
    Workspace.Kind workspaceKind,
    int version,
    Long lastUpdate,
    Long resourceCount) {}
