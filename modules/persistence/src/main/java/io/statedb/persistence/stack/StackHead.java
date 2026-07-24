package io.statedb.persistence.stack;

import java.util.Map;
import java.util.UUID;

/**
 * The full stack-head projection consumed by Pulumi's {@code GET
 * /api/stacks/{org}/{project}/{stack}} and (later) the console drilldown. Carries every field
 * either caller might surface; each HTTP module maps to its own wire DTO so additions here don't
 * leak into the Pulumi wire shape.
 *
 * <p>Placeholder fields ({@code lastUpdate}, {@code resourceCount}) are populated by later work.
 */
public record StackHead(
    UUID stackId,
    UUID orgId,
    String orgName,
    String projectName,
    String stackName,
    Map<String, String> tags,
    int version,
    String activeUpdate,
    Object lastUpdate,
    int resourceCount) {}
