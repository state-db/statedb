package io.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcquireLockResponse(
    @JsonProperty("token") String token,
    @JsonProperty("tokenExpiration") long tokenExpiration,
    @JsonProperty("version") long version,
    @JsonProperty("journalVersion") Integer journalVersion) {}
