package io.statedb.persistence.update;

import java.util.Map;

/**
 * Inputs to {@link StackUpdateStarter#start} sourced from the Pulumi {@code UpdateProgramRequest}
 * body (POST .../update|preview|destroy). Lives in {@code persistence/} so callers in any HTTP
 * module use the same shape — the service does the writes; the resource only translates the wire
 * payload.
 */
public record StartUpdateRequest(
    Map<String, Object> config,
    Map<String, Object> metadata,
    Map<String, Object> options,
    String description,
    String runtime,
    String pulumiVersion,
    String message) {}
