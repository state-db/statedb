package io.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Wire shape of the Pulumi {@code UpdateProgramRequest} body (POST .../update|preview|destroy). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateProgramRequest {

  @JsonProperty("name")
  public String name;

  @JsonProperty("orgName")
  public String orgName;

  @JsonProperty("projectName")
  public String projectName;

  @JsonProperty("stackName")
  public String stackName;

  @JsonProperty("config")
  public Map<String, Object> config;

  @JsonProperty("description")
  public String description;

  @JsonProperty("metadata")
  public Map<String, Object> metadata;

  @JsonProperty("options")
  public Map<String, Object> options;

  @JsonProperty("runtime")
  public String runtime;
}
