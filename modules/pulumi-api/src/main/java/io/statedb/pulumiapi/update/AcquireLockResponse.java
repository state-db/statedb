package io.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AcquireLockResponse(
    @JsonProperty("token") String token, @JsonProperty("duration") long duration) {}
