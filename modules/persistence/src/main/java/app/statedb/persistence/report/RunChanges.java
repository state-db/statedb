package app.statedb.persistence.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The canonical interpretation of one Pulumi engine-event stream.
 *
 * <p>This is richer than {@link ChangeReport}: the authenticated console may display redacted
 * values, while a pull-request report deliberately projects only paths and secret markers. Both
 * surfaces consume this model so event ordering, replacement folding, property selection, {@code
 * hideDiffs}, and secret recognition have one owner.
 */
public record RunChanges(
    List<ResourceChange> changed,
    List<ResourceChange> unchanged,
    List<ResourceChange> activity,
    List<OutputChange> outputs) {

  public RunChanges {
    changed = changed == null ? List.of() : List.copyOf(changed);
    unchanged = unchanged == null ? List.of() : List.copyOf(unchanged);
    activity = activity == null ? List.of() : List.copyOf(activity);
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }

  public record ResourceChange(
      int sequence,
      String urn,
      String type,
      String name,
      String op,
      List<PropertyChange> properties) {

    public ResourceChange {
      properties = properties == null ? List.of() : List.copyOf(properties);
    }
  }

  /** Values are recursively redacted before they cross this interface. */
  public record PropertyChange(
      String path,
      String kind,
      boolean internal,
      boolean secret,
      boolean oldPresent,
      JsonNode oldValue,
      boolean newPresent,
      JsonNode newValue) {}

  public record OutputChange(
      String key,
      String kind,
      boolean oldPresent,
      JsonNode oldValue,
      boolean newPresent,
      JsonNode newValue) {}
}
