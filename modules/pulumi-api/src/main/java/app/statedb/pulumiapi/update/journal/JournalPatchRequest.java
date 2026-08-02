package app.statedb.pulumiapi.update.journal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Wire body of {@code PATCH .../update/{updateID}/journalentries}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JournalPatchRequest {

  /**
   * Raw entries as JsonNode — typed fields are pulled out by {@link JournalEntryView}; everything
   * else is preserved verbatim in the row's {@code payload} column so we don't lose anything the
   * CLI sent that we don't yet model.
   */
  @JsonProperty("entries")
  public List<JsonNode> entries;
}
