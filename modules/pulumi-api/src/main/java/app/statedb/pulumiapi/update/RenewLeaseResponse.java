package app.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RenewLeaseResponse(
    @JsonProperty("token") String token, @JsonProperty("tokenExpiration") long tokenExpiration) {}
