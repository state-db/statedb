package app.statedb.pulumiapi.wire;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Organization identity nested in Pulumi's user and default-organization responses. */
public record OrganizationReference(
    @JsonProperty("githubLogin") String githubLogin, @JsonProperty("name") String name) {}
