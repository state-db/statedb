package app.statedb.pulumiapi.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RenewLeaseRequest {

  @JsonProperty("duration")
  public Long duration;

  // Deprecated: the lock token now travels in the Authorization header. Pulumi may still send it
  // here for older clients; the handler validates that any supplied value matches the header.
  @JsonProperty("token")
  public String token;
}
