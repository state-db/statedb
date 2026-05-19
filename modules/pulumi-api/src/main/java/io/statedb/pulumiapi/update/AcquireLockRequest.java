package io.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AcquireLockRequest {

  @JsonProperty("journalVersion")
  public Integer journalVersion;

  @JsonProperty("tags")
  public Map<String, Object> tags;
}
