package app.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StartUpdateResponse(
    @JsonProperty("updateID") String updateID, @JsonProperty("version") int version) {}
