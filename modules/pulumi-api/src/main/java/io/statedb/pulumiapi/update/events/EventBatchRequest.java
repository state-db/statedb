package io.statedb.pulumiapi.update.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Wire body of {@code POST .../update/{updateID}/events/batch}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventBatchRequest {

  /**
   * Raw events as JsonNode — typed fields are pulled out by {@code UpdateEventView}; the full node
   * is preserved in the row's {@code payload} column so we don't lose anything the CLI sent that we
   * don't yet model.
   */
  @JsonProperty("events")
  public List<JsonNode> events;
}
