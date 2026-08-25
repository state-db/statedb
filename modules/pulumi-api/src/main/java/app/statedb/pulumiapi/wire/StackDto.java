package app.statedb.pulumiapi.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** The recorded Pulumi service stack shape emitted by stack create and stack head. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StackDto(
    @JsonProperty("activeUpdate") String activeUpdate,
    @JsonProperty("currentOperation") Object currentOperation,
    @JsonProperty("orgName") String orgName,
    @JsonProperty("projectName") String projectName,
    @JsonProperty("stackName") String stackName,
    @JsonProperty("tags") Map<String, String> tags) {}
