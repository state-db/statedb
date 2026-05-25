package io.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Wire body of {@code POST .../update/{updateID}/complete}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompleteUpdateRequest {

  @JsonProperty("status")
  public String status;
}
